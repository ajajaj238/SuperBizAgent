package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.QaCacheProperties;
import org.example.dto.QaCacheEvalDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * QA 缓存阈值离线评测。
 * 启用后（qa-cache.evaluation.enabled=true）在启动时：
 * 1. 对评测样本批量生成 embedding；
 * 2. 计算 HIT/MISS 用例在各阈值下的准确率，输出推荐阈值；
 * 3. 校验 DYNAMIC 用例是否被读侧闸门正确拦截。
 */
@Component
public class QaCacheEvaluationService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(QaCacheEvaluationService.class);
    private static final Logger evalLogger = LoggerFactory.getLogger("ai.qa_cache.eval");
    private static final List<Double> THRESHOLDS = List.of(0.80, 0.85, 0.88, 0.90, 0.92, 0.95);
    private static final int EMBED_BATCH = 10;

    private final QaCacheProperties properties;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final VectorEmbeddingService embeddingService;
    private final QaCacheService qaCacheService;

    public QaCacheEvaluationService(QaCacheProperties properties,
                                    ObjectMapper objectMapper,
                                    ResourceLoader resourceLoader,
                                    VectorEmbeddingService embeddingService,
                                    QaCacheService qaCacheService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.embeddingService = embeddingService;
        this.qaCacheService = qaCacheService;
    }

    @Override
    public void run(ApplicationArguments args) {
        QaCacheProperties.Evaluation eval = properties.getEvaluation();
        if (eval == null || !eval.isEnabled()) {
            return;
        }
        try {
            Resource resource = resourceLoader.getResource(eval.getDatasetLocation());
            if (!resource.exists()) {
                logger.warn("QA缓存评测数据集不存在: {}", eval.getDatasetLocation());
                return;
            }
            QaCacheEvalDataset dataset;
            try (InputStream in = resource.getInputStream()) {
                dataset = objectMapper.readValue(in, QaCacheEvalDataset.class);
            }
            runEvaluation(dataset);
        } catch (Exception e) {
            logger.error("QA缓存阈值评测失败", e);
        }
    }

    private void runEvaluation(QaCacheEvalDataset dataset) {
        List<String> canonicals = uniqueCanonicals(dataset);
        List<QaCacheEvalDataset.QaCacheEvalCase> cases = dataset.getCases();
        if (canonicals.isEmpty() || cases == null || cases.isEmpty()) {
            logger.warn("QA缓存评测数据集为空，跳过");
            return;
        }

        logger.info("QA缓存评测开始: canonicals={}, cases={}", canonicals.size(), cases.size());
        long start = System.currentTimeMillis();

        List<List<Float>> canonicalVectors = embedInBatches(canonicals);
        Map<String, List<Float>> canonicalVectorMap = new LinkedHashMap<>();
        for (int i = 0; i < canonicals.size(); i++) {
            canonicalVectorMap.put(canonicals.get(i), canonicalVectors.get(i));
        }

        List<String> queries = cases.stream()
                .map(c -> c.getQuery() == null ? "" : c.getQuery())
                .collect(Collectors.toList());
        List<List<Float>> queryVectors = embedInBatches(queries);

        List<CaseResult> hitCases = new ArrayList<>();
        List<CaseResult> missCases = new ArrayList<>();
        List<CaseResult> dynamicCases = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            QaCacheEvalDataset.QaCacheEvalCase evalCase = cases.get(i);
            String normalizedQuery = qaCacheService.normalize(evalCase.getQuery());
            CaseResult result = new CaseResult(evalCase, normalizedQuery);

            if ("DYNAMIC".equalsIgnoreCase(evalCase.getExpected())) {
                result.blocked = qaCacheService.isDynamicQuestion(normalizedQuery);
                dynamicCases.add(result);
                continue;
            }

            List<Similarity> sims = new ArrayList<>();
            for (Map.Entry<String, List<Float>> entry : canonicalVectorMap.entrySet()) {
                float score = embeddingService.calculateCosineSimilarity(queryVectors.get(i), entry.getValue());
                sims.add(new Similarity(entry.getKey(), score));
            }
            sims.sort(Comparator.comparingDouble(Similarity::score).reversed());
            result.top1 = sims.get(0).canonical();
            result.top1Score = sims.get(0).score();
            result.top1SubjectConsistent =
                    qaCacheService.isSubjectConsistent(normalizedQuery, result.top1);

            String targetCanonical = evalCase.getCanonical();
            if (targetCanonical != null && !targetCanonical.isBlank()) {
                List<Float> targetVector = canonicalVectorMap.get(targetCanonical);
                if (targetVector != null) {
                    result.ownOrDecoyScore =
                            embeddingService.calculateCosineSimilarity(queryVectors.get(i), targetVector);
                    result.decoySubjectConsistent =
                            qaCacheService.isSubjectConsistent(normalizedQuery, targetCanonical);
                }
            }

            if ("HIT".equalsIgnoreCase(evalCase.getExpected())) {
                hitCases.add(result);
            } else {
                missCases.add(result);
            }
        }

        List<ThresholdResult> sweep = sweepThresholds(hitCases, missCases);
        int dynamicCorrect = (int) dynamicCases.stream().filter(r -> r.blocked).count();

        for (CaseResult result : hitCases) {
            logCase(result, "HIT");
        }
        for (CaseResult result : missCases) {
            logCase(result, "MISS");
        }
        for (CaseResult result : dynamicCases) {
            logCase(result, "DYNAMIC");
        }
        for (ThresholdResult result : sweep) {
            logThreshold(result, hitCases.size(), missCases.size(), dynamicCases.size(), dynamicCorrect);
        }
        logNearFormPairs(canonicalVectorMap);

        ThresholdResult best = Collections.max(sweep,
                Comparator.comparingDouble(ThresholdResult::accuracy).thenComparing(Comparator.comparingDouble(
                        (ThresholdResult r) -> -r.threshold())));
        logger.info("QA缓存评测完成，耗时 {} ms；推荐阈值: {}（准确率: {}）",
                System.currentTimeMillis() - start,
                best.threshold(),
                String.format("%.3f", best.accuracy()));
        logger.info("QA缓存评测完成: HIT={}, MISS={}, DYNAMIC={}（拦截 {}/{}）",
                hitCases.size(), missCases.size(), dynamicCases.size(), dynamicCorrect, dynamicCases.size());
    }

    private List<ThresholdResult> sweepThresholds(List<CaseResult> hitCases, List<CaseResult> missCases) {
        Set<Double> thresholds = new LinkedHashSet<>(THRESHOLDS);
        thresholds.add(properties.getSimilarityThreshold());
        List<Double> ordered = new ArrayList<>(thresholds);
        Collections.sort(ordered);

        List<ThresholdResult> results = new ArrayList<>();
        int total = hitCases.size() + missCases.size();
        for (double threshold : ordered) {
            int hitCorrect = 0;
            int hitCorrectCosineOnly = 0;
            for (CaseResult result : hitCases) {
                if (result.top1Score >= threshold) {
                    hitCorrectCosineOnly++;
                }
                if (result.top1Score >= threshold && result.top1SubjectConsistent) {
                    hitCorrect++;
                }
            }
            int missCorrect = 0;
            int missCorrectCosineOnly = 0;
            for (CaseResult result : missCases) {
                if (result.ownOrDecoyScore < threshold) {
                    missCorrect++;
                    missCorrectCosineOnly++;
                } else if (!result.decoySubjectConsistent) {
                    missCorrect++;
                }
            }
            double accuracy = total == 0 ? 0.0d : (double) (hitCorrect + missCorrect) / total;
            double accuracyCosineOnly = total == 0 ? 0.0d : (double) (hitCorrectCosineOnly + missCorrectCosineOnly) / total;
            results.add(new ThresholdResult(threshold, hitCorrect, missCorrect, accuracy, accuracyCosineOnly));
        }
        return results;
    }

    private void logCase(CaseResult result, String type) {
        QaCacheEvalDataset.QaCacheEvalCase evalCase = result.evalCase;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "qa_cache_evaluation");
        payload.put("caseId", evalCase.getId());
        payload.put("type", type);
        payload.put("query", evalCase.getQuery());
        payload.put("normalizedQuery", result.normalizedQuery);
        payload.put("canonical", evalCase.getCanonical());
        payload.put("expected", evalCase.getExpected());
        if ("DYNAMIC".equalsIgnoreCase(evalCase.getExpected())) {
            payload.put("blocked", result.blocked);
            payload.put("correct", result.blocked);
        } else if ("HIT".equalsIgnoreCase(evalCase.getExpected())) {
            payload.put("top1", result.top1);
            payload.put("top1Score", round(result.top1Score));
            payload.put("top1SubjectConsistent", result.top1SubjectConsistent);
        } else {
            payload.put("top1", result.top1);
            payload.put("top1Score", round(result.top1Score));
            payload.put("decoyScore", round(result.ownOrDecoyScore));
            payload.put("decoySubjectConsistent", result.decoySubjectConsistent);
        }
        payload.put("note", evalCase.getNote());
        writeEvalLog(payload);
    }

    private void logThreshold(ThresholdResult result, int hitTotal, int missTotal, int dynamicTotal, int dynamicCorrect) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "qa_cache_threshold");
        payload.put("threshold", result.threshold());
        payload.put("hitCorrect", result.hitCorrect());
        payload.put("hitTotal", hitTotal);
        payload.put("missCorrect", result.missCorrect());
        payload.put("missTotal", missTotal);
        payload.put("dynamicCorrect", dynamicCorrect);
        payload.put("dynamicTotal", dynamicTotal);
        payload.put("accuracy", round(result.accuracy()));
        payload.put("accuracyCosineOnly", round(result.accuracyCosineOnly()));
        writeEvalLog(payload);
    }

    private void logNearFormPairs(Map<String, List<Float>> canonicalVectorMap) {
        List<String> names = new ArrayList<>(canonicalVectorMap.keySet());
        List<Similarity> pairs = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                float score = embeddingService.calculateCosineSimilarity(
                        canonicalVectorMap.get(names.get(i)), canonicalVectorMap.get(names.get(j)));
                pairs.add(new Similarity(names.get(i) + " <-> " + names.get(j), score));
            }
        }
        pairs.sort(Comparator.comparingDouble(Similarity::score).reversed());
        List<Map<String, Object>> topPairs = new ArrayList<>();
        for (int i = 0; i < Math.min(8, pairs.size()); i++) {
            Similarity pair = pairs.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pair", pair.canonical());
            item.put("score", round(pair.score()));
            topPairs.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "qa_cache_near_form_pairs");
        payload.put("pairs", topPairs);
        writeEvalLog(payload);
    }

    private List<String> uniqueCanonicals(QaCacheEvalDataset dataset) {
        Set<String> unique = new LinkedHashSet<>();
        if (dataset.getCanonicals() != null) {
            for (String canonical : dataset.getCanonicals()) {
                if (canonical != null && !canonical.isBlank()) {
                    unique.add(canonical);
                }
            }
        }
        if (dataset.getCases() != null) {
            for (QaCacheEvalDataset.QaCacheEvalCase evalCase : dataset.getCases()) {
                if (evalCase.getCanonical() != null && !evalCase.getCanonical().isBlank()) {
                    unique.add(evalCase.getCanonical());
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private List<List<Float>> embedInBatches(List<String> texts) {
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            int end = Math.min(i + EMBED_BATCH, texts.size());
            vectors.addAll(embeddingService.generateEmbeddings(texts.subList(i, end)));
        }
        return vectors;
    }

    private void writeEvalLog(Map<String, Object> payload) {
        try {
            evalLogger.info(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            logger.warn("写入QA缓存评测日志失败: {}", e.getMessage());
        }
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private record Similarity(String canonical, float score) {
    }

    private record ThresholdResult(double threshold, int hitCorrect, int missCorrect,
                                   double accuracy, double accuracyCosineOnly) {
    }

    private static class CaseResult {
        private final QaCacheEvalDataset.QaCacheEvalCase evalCase;
        private final String normalizedQuery;
        private String top1;
        private float top1Score;
        private float ownOrDecoyScore;
        private boolean blocked;
        private boolean top1SubjectConsistent;
        private boolean decoySubjectConsistent;

        private CaseResult(QaCacheEvalDataset.QaCacheEvalCase evalCase, String normalizedQuery) {
            this.evalCase = evalCase;
            this.normalizedQuery = normalizedQuery;
        }
    }
}
