package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Query Rewrite 离线评测。
 * 启用后（rewrite.evaluation.enabled=true）在启动时：
 * 1. 对多轮对话用例调用真实 QueryRewriteService（LLM+规则兜底+主题一致性校验）；
 * 2. 用 golden 改写对比计算改写准确率（flag 一致性 + embedding 相似度）；
 * 3. 跑 RAG 检索：原始单路 vs 原始+改写双路 的 hit@4，以及重排前后的 hit@4；
 * 4. 附加模型级 reranker（qwen3-rerank）top1 命中率，输出汇总报告。
 */
@Component
public class RewriteEvaluationService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(RewriteEvaluationService.class);
    private static final Logger evalLogger = LoggerFactory.getLogger("ai.rewrite.eval");
    private static final double REWRITE_SIM_THRESHOLD = 0.85;
    private static final int RETRIEVAL_TOP_K = 8;
    private static final int FINAL_TOP_K = 4;
    private static final int MODEL_RERANK_CANDIDATES = 5;

    @Value("${rewrite.evaluation.enabled:false}")
    private boolean enabled;

    @Value("${rewrite.evaluation.dataset-location:classpath:rewrite-eval.json}")
    private String datasetLocation;

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final QueryRewriteService queryRewriteService;
    private final VectorSearchService vectorSearchService;
    private final RagRerankService ragRerankService;
    private final VectorEmbeddingService embeddingService;
    private final RerankService rerankService;

    public RewriteEvaluationService(ObjectMapper objectMapper,
                                    ResourceLoader resourceLoader,
                                    QueryRewriteService queryRewriteService,
                                    VectorSearchService vectorSearchService,
                                    RagRerankService ragRerankService,
                                    VectorEmbeddingService embeddingService,
                                    RerankService rerankService) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.queryRewriteService = queryRewriteService;
        this.vectorSearchService = vectorSearchService;
        this.ragRerankService = ragRerankService;
        this.embeddingService = embeddingService;
        this.rerankService = rerankService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            logger.info("Query Rewrite 评测未启用（rewrite.evaluation.enabled=false）");
            return;
        }
        try {
            Resource resource = resourceLoader.getResource(datasetLocation);
            if (!resource.exists()) {
                logger.warn("Query Rewrite 评测数据集不存在: {}", datasetLocation);
                return;
            }
            EvalDataset dataset;
            try (InputStream in = resource.getInputStream()) {
                dataset = objectMapper.readValue(in, EvalDataset.class);
            }
            runEvaluation(dataset);
        } catch (Exception e) {
            logger.error("Query Rewrite 评测失败", e);
        }
    }

    private void runEvaluation(EvalDataset dataset) throws Exception {
        List<EvalCase> cases = dataset.getCases();
        if (cases == null || cases.isEmpty()) {
            logger.warn("Query Rewrite 评测集为空");
            return;
        }
        logger.info("Query Rewrite 评测开始: 用例数={}", cases.size());

        // 1) 真实调用 QueryRewriteService
        List<RewriteOutcome> outcomes = new ArrayList<>();
        Set<String> textsToEmbed = new LinkedHashSet<>();
        for (EvalCase evalCase : cases) {
            QueryRewriteService.RewriteResult result =
                    queryRewriteService.rewriteForRetrieval(evalCase.question, evalCase.history);
            outcomes.add(new RewriteOutcome(evalCase, result));
            textsToEmbed.add(evalCase.question);
            textsToEmbed.add(evalCase.expected);
            if (result.rewritten()) {
                textsToEmbed.add(result.rewrittenQuery());
            }
        }

        // 2) embedding（批量）
        List<String> textList = new ArrayList<>(textsToEmbed);
        List<List<Float>> vectors = embedInBatches(textList);
        Map<String, List<Float>> vectorMap = new HashMap<>();
        for (int i = 0; i < textList.size(); i++) {
            vectorMap.put(textList.get(i), vectors.get(i));
        }

        // 3) 逐用例评估
        int total = 0;
        int correct = 0;
        int flagTotal = 0;
        int flagOk = 0;
        int relTotal = 0;
        int origHit = 0;
        int dualHit = 0;
        int candidateHit = 0;
        int rerankHit = 0;
        int cosineTop1Hit = 0;
        int rerankTop1Hit = 0;
        int modelRerankTop1Hit = 0;

        for (RewriteOutcome outcome : outcomes) {
            EvalCase evalCase = outcome.evalCase();
            QueryRewriteService.RewriteResult result = outcome.result();

            total++;
            flagTotal++;
            boolean flagCorrect = result.rewritten() == evalCase.needRewrite;
            if (flagCorrect) {
                flagOk++;
            }
            float sim = embeddingService.calculateCosineSimilarity(
                    vectorMap.get(result.rewrittenQuery()), vectorMap.get(evalCase.expected));
            boolean caseCorrect = flagCorrect && sim >= REWRITE_SIM_THRESHOLD;
            if (caseCorrect) {
                correct++;
            }

            boolean origHitFlag = false;
            boolean dualHitFlag = false;
            boolean candHitFlag = false;
            boolean rerankHitFlag = false;
            boolean cosineTop1Flag = false;
            boolean rerankTop1Flag = false;
            boolean modelTop1Flag = false;

            if (!evalCase.relevantFileNames.isEmpty()) {
                relTotal++;
                List<VectorSearchService.SearchResult> origCandidates =
                        vectorSearchService.searchSimilarDocuments(evalCase.question, RETRIEVAL_TOP_K);
                List<VectorSearchService.SearchResult> origReranked =
                        ragRerankService.rerankAndFilter(evalCase.question, origCandidates, FINAL_TOP_K);
                origHitFlag = hitAtK(origReranked, evalCase.relevantFileNames);
                if (origHitFlag) {
                    origHit++;
                }

                List<VectorSearchService.SearchResult> merged = mergeCandidates(origCandidates);
                if (result.rewritten() && !result.rewrittenQuery().equals(evalCase.question)) {
                    List<VectorSearchService.SearchResult> rewrittenCandidates =
                            vectorSearchService.searchSimilarDocuments(result.rewrittenQuery(), RETRIEVAL_TOP_K);
                    merged = mergeCandidates(merged, rewrittenCandidates);
                }
                merged.sort(Comparator.comparingDouble(
                        (VectorSearchService.SearchResult r) -> (double) r.getScore()).reversed());
                candHitFlag = hitAtK(limit(merged, FINAL_TOP_K), evalCase.relevantFileNames);
                if (candHitFlag) {
                    candidateHit++;
                }
                cosineTop1Flag = !merged.isEmpty()
                        && isRelevant(merged.get(0), evalCase.relevantFileNames);
                if (cosineTop1Flag) {
                    cosineTop1Hit++;
                }

                String rerankQuery = result.rewritten() ? result.rewrittenQuery() : evalCase.question;
                List<VectorSearchService.SearchResult> dualReranked =
                        ragRerankService.rerankAndFilter(rerankQuery, merged, FINAL_TOP_K);
                rerankHitFlag = hitAtK(dualReranked, evalCase.relevantFileNames);
                dualHitFlag = rerankHitFlag;
                if (rerankHitFlag) {
                    dualHit++;
                    rerankHit++;
                }
                rerankTop1Flag = !dualReranked.isEmpty()
                        && isRelevant(dualReranked.get(0), evalCase.relevantFileNames);
                if (rerankTop1Flag) {
                    rerankTop1Hit++;
                }

                try {
                    if (!merged.isEmpty()) {
                        List<VectorSearchService.SearchResult> modelCandidates =
                                limit(merged, MODEL_RERANK_CANDIDATES);
                        List<String> docs = modelCandidates.stream()
                                .map(VectorSearchService.SearchResult::getContent)
                                .toList();
                        List<RerankService.RerankScore> scores = rerankService.rerank(rerankQuery, docs);
                        if (!scores.isEmpty()) {
                            int index = scores.get(0).index();
                            if (index >= 0 && index < modelCandidates.size()
                                    && isRelevant(modelCandidates.get(index), evalCase.relevantFileNames)) {
                                modelTop1Flag = true;
                                modelRerankTop1Hit++;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("模型 rerank 评测失败: {}", e.getMessage());
                }
            }

            logCase(outcome, sim, flagCorrect, caseCorrect,
                    origHitFlag, dualHitFlag, candHitFlag, rerankHitFlag,
                    cosineTop1Flag, rerankTop1Flag, modelTop1Flag);
        }

        int rewriteCases = total;
        int retrievalCases = relTotal;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("event", "rewrite_evaluation_summary");
        summary.put("total", total);
        summary.put("rewriteCorrect", correct);
        summary.put("rewriteAccuracy", round(percent(correct, rewriteCases)));
        summary.put("flagCorrect", flagOk);
        summary.put("flagAccuracy", round(percent(flagOk, flagTotal)));
        summary.put("retrievalCases", retrievalCases);
        summary.put("originalOnlyHit4", round(percent(origHit, retrievalCases)));
        summary.put("dualPathHit4", round(percent(dualHit, retrievalCases)));
        summary.put("candidateHit4", round(percent(candidateHit, retrievalCases)));
        summary.put("rerankHit4", round(percent(rerankHit, retrievalCases)));
        summary.put("cosineTop1Hit", round(percent(cosineTop1Hit, retrievalCases)));
        summary.put("rerankTop1Hit", round(percent(rerankTop1Hit, retrievalCases)));
        summary.put("modelRerankTop1Hit", round(percent(modelRerankTop1Hit, retrievalCases)));
        summary.put("dualRecallImprovement", round(percent(dualHit - origHit, retrievalCases)));
        summary.put("rerankTop1Improvement", round(percent(rerankTop1Hit - cosineTop1Hit, retrievalCases)));
        writeEvalLog(summary);

        logger.info("Query Rewrite 评测完成: 改写准确率={}%，双路召回 hit@4={}%（单路 {}%），重排 top1 相关率={}%（余弦 top1 {}%），模型rerank top1={}%",
                round(percent(correct, rewriteCases)),
                round(percent(dualHit, retrievalCases)),
                round(percent(origHit, retrievalCases)),
                round(percent(rerankTop1Hit, retrievalCases)),
                round(percent(cosineTop1Hit, retrievalCases)),
                round(percent(modelRerankTop1Hit, retrievalCases)));
    }

    private void logCase(RewriteOutcome outcome, float sim, boolean flagCorrect, boolean caseCorrect,
                         boolean origHit, boolean dualHit, boolean candHit, boolean rerankHit,
                         boolean cosineTop1, boolean rerankTop1, boolean modelTop1) {
        EvalCase evalCase = outcome.evalCase();
        QueryRewriteService.RewriteResult result = outcome.result();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "rewrite_evaluation_case");
        payload.put("id", evalCase.id);
        payload.put("question", evalCase.question);
        payload.put("rewritten", result.rewritten());
        payload.put("rewrittenQuery", result.rewrittenQuery());
        payload.put("method", result.method());
        payload.put("reason", result.reason());
        payload.put("expected", evalCase.expected);
        payload.put("needRewrite", evalCase.needRewrite);
        payload.put("flagCorrect", flagCorrect);
        payload.put("sim", round(sim));
        payload.put("correct", caseCorrect);
        payload.put("origHit", origHit);
        payload.put("dualHit", dualHit);
        payload.put("candidateHit", candHit);
        payload.put("rerankHit", rerankHit);
        payload.put("cosineTop1", cosineTop1);
        payload.put("rerankTop1", rerankTop1);
        payload.put("modelRerankTop1", modelTop1);
        writeEvalLog(payload);
    }

    private List<List<Float>> embedInBatches(List<String> texts) {
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 10) {
            int end = Math.min(i + 10, texts.size());
            vectors.addAll(embeddingService.generateEmbeddings(texts.subList(i, end)));
        }
        return vectors;
    }

    private List<VectorSearchService.SearchResult> mergeCandidates(
            List<VectorSearchService.SearchResult> first,
            List<VectorSearchService.SearchResult>... others) {
        Map<String, VectorSearchService.SearchResult> merged = new LinkedHashMap<>();
        mergeInto(merged, first);
        for (List<VectorSearchService.SearchResult> list : others) {
            mergeInto(merged, list);
        }
        return new ArrayList<>(merged.values());
    }

    private void mergeInto(Map<String, VectorSearchService.SearchResult> merged,
                           List<VectorSearchService.SearchResult> results) {
        if (results == null) {
            return;
        }
        for (VectorSearchService.SearchResult result : results) {
            if (result == null) {
                continue;
            }
            String key = result.getId() != null && !result.getId().isBlank()
                    ? result.getId()
                    : result.getContent();
            if (key == null || key.isBlank()) {
                continue;
            }
            VectorSearchService.SearchResult existing = merged.get(key);
            if (existing == null || result.getScore() > existing.getScore()) {
                merged.put(key, result);
            }
        }
    }

    private boolean hitAtK(List<VectorSearchService.SearchResult> results, List<String> relevantFileNames) {
        for (VectorSearchService.SearchResult result : results) {
            if (isRelevant(result, relevantFileNames)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRelevant(VectorSearchService.SearchResult result, List<String> relevantFileNames) {
        if (result == null || result.getMetadata() == null || result.getMetadata().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(
                    result.getMetadata(), new TypeReference<Map<String, Object>>() {
                    });
            String fileName = normalize(String.valueOf(metadata.getOrDefault("_file_name", "")));
            String source = normalize(String.valueOf(metadata.getOrDefault("_source", "")));
            for (String relevant : relevantFileNames) {
                String expected = normalize(relevant);
                if ((!fileName.isEmpty() && fileName.contains(expected))
                        || source.endsWith(expected)) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.warn("解析检索 metadata 失败: {}", e.getMessage());
        }
        return false;
    }

    private List<VectorSearchService.SearchResult> limit(
            List<VectorSearchService.SearchResult> input, int max) {
        if (input == null || input.isEmpty() || max <= 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(input.subList(0, Math.min(max, input.size())));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replace("\\", "/").toLowerCase(Locale.ROOT);
    }

    private double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator * 100.0;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private void writeEvalLog(Map<String, Object> payload) {
        try {
            evalLogger.info(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            logger.warn("写入改写评测日志失败: {}", e.getMessage());
        }
    }

    private record RewriteOutcome(EvalCase evalCase, QueryRewriteService.RewriteResult result) {
    }

    public static class EvalDataset {
        private List<EvalCase> cases = new ArrayList<>();

        public List<EvalCase> getCases() {
            return cases;
        }

        public void setCases(List<EvalCase> cases) {
            this.cases = cases;
        }
    }

    public static class EvalCase {
        private String id;
        private List<Map<String, String>> history = new ArrayList<>();
        private String question;
        private String expected;
        private boolean needRewrite;
        private List<String> relevantFileNames = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public List<Map<String, String>> getHistory() {
            return history;
        }

        public void setHistory(List<Map<String, String>> history) {
            this.history = history;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getExpected() {
            return expected;
        }

        public void setExpected(String expected) {
            this.expected = expected;
        }

        public boolean isNeedRewrite() {
            return needRewrite;
        }

        public void setNeedRewrite(boolean needRewrite) {
            this.needRewrite = needRewrite;
        }

        public List<String> getRelevantFileNames() {
            return relevantFileNames;
        }

        public void setRelevantFileNames(List<String> relevantFileNames) {
            this.relevantFileNames = relevantFileNames;
        }
    }
}
