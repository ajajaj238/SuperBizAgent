package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.example.config.RagEvaluationProperties;
import org.example.dto.RetrievalEvalDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RetrievalEvaluationService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalEvaluationService.class);
    private static final Logger evalLogger = LoggerFactory.getLogger("ai.retrieval.eval");

    private final RagEvaluationProperties properties;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    private final Map<String, RetrievalEvalDataset.RetrievalEvalCase> caseIndex = new ConcurrentHashMap<>();

    public RetrievalEvaluationService(RagEvaluationProperties properties,
                                      ObjectMapper objectMapper,
                                      ResourceLoader resourceLoader) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void loadDataset() {
        caseIndex.clear();

        if (!properties.isEnabled()) {
            logger.info("离线检索测评集未启用，跳过加载");
            return;
        }

        String datasetLocation = properties.getDatasetLocation();
        if (datasetLocation == null || datasetLocation.isBlank()) {
            logger.warn("检索评测集路径为空，跳过加载");
            return;
        }

        try {
            Resource resource = resourceLoader.getResource(datasetLocation);
            if (!resource.exists()) {
                logger.warn("检索评测集不存在: {}", datasetLocation);
                return;
            }

            try (InputStream inputStream = resource.getInputStream()) {
                RetrievalEvalDataset dataset = objectMapper.readValue(inputStream, RetrievalEvalDataset.class);
                int loadedCases = 0;
                for (RetrievalEvalDataset.RetrievalEvalCase evalCase : dataset.getCases()) {
                    if (!isValidCase(evalCase)) {
                        logger.warn("跳过无效检索评测样本: id={}, query={}", evalCase.getId(), evalCase.getQuery());
                        continue;
                    }

                    indexQuery(evalCase.getQuery(), evalCase);
                    for (String alias : evalCase.getAliases()) {
                        indexQuery(alias, evalCase);
                    }
                    loadedCases++;
                }
                logger.info("检索评测集加载完成: {}, 样本数: {}, 可匹配问法数: {}",
                        datasetLocation, loadedCases, caseIndex.size());
            }
        } catch (Exception e) {
            logger.error("加载检索评测集失败: {}", datasetLocation, e);
        }
    }

    public void evaluateAndLog(String query, String stage, int topK, List<VectorSearchService.SearchResult> results) {
        if (!properties.isEnabled()) {
            return;
        }

        RetrievalEvalDataset.RetrievalEvalCase evalCase = findCase(query);
        if (evalCase == null) {
            if (properties.isLogNoLabels()) {
                logSkipped(query, stage, topK, results, "no_matching_eval_case");
            }
            return;
        }

        Set<String> relevantSources = normalizeAll(evalCase.getRelevantSources());
        Set<String> relevantFileNames = normalizeAll(evalCase.getRelevantFileNames());

        int matchedRank = 0;
        List<String> retrievedSources = new ArrayList<>();
        List<String> retrievedFileNames = new ArrayList<>();

        for (int i = 0; i < results.size(); i++) {
            SearchResultMetadata metadata = extractMetadata(results.get(i));
            retrievedSources.add(metadata.source());
            retrievedFileNames.add(metadata.fileName());

            if (matchedRank == 0 && isRelevant(metadata, relevantSources, relevantFileNames)) {
                matchedRank = i + 1;
            }
        }

        double hitAtK = matchedRank > 0 && matchedRank <= topK ? 1.0d : 0.0d;
        double mrr = matchedRank > 0 ? 1.0d / matchedRank : 0.0d;

        Map<String, Object> logPayload = new LinkedHashMap<>();
        logPayload.put("event", "retrieval_evaluation");
        logPayload.put("evalCaseId", evalCase.getId());
        logPayload.put("query", query);
        logPayload.put("canonicalQuery", evalCase.getQuery());
        logPayload.put("stage", stage);
        logPayload.put("topK", topK);
        logPayload.put("resultCount", results.size());
        logPayload.put("hitAtK", hitAtK);
        logPayload.put("mrr", mrr);
        logPayload.put("matchedRank", matchedRank == 0 ? null : matchedRank);
        logPayload.put("relevantSources", evalCase.getRelevantSources());
        logPayload.put("relevantFileNames", evalCase.getRelevantFileNames());
        logPayload.put("retrievedSources", retrievedSources);
        logPayload.put("retrievedFileNames", retrievedFileNames);

        writeEvalLog(logPayload);
        logger.info("检索评估完成, caseId: {}, stage: {}, hit@{}: {}, mrr: {}",
                evalCase.getId(), stage, topK, hitAtK, mrr);
    }

    private RetrievalEvalDataset.RetrievalEvalCase findCase(String query) {
        return caseIndex.get(normalizeText(query));
    }

    private void logSkipped(String query, String stage, int topK, List<VectorSearchService.SearchResult> results, String reason) {
        List<String> retrievedSources = new ArrayList<>();
        List<String> retrievedFileNames = new ArrayList<>();
        for (VectorSearchService.SearchResult result : results) {
            SearchResultMetadata metadata = extractMetadata(result);
            retrievedSources.add(metadata.source());
            retrievedFileNames.add(metadata.fileName());
        }

        Map<String, Object> logPayload = new LinkedHashMap<>();
        logPayload.put("event", "retrieval_evaluation");
        logPayload.put("query", query);
        logPayload.put("stage", stage);
        logPayload.put("topK", topK);
        logPayload.put("resultCount", results.size());
        logPayload.put("evaluated", false);
        logPayload.put("reason", reason);
        logPayload.put("hitAtK", null);
        logPayload.put("mrr", null);
        logPayload.put("retrievedSources", retrievedSources);
        logPayload.put("retrievedFileNames", retrievedFileNames);
        writeEvalLog(logPayload);
    }

    private boolean isValidCase(RetrievalEvalDataset.RetrievalEvalCase evalCase) {
        if (evalCase == null || evalCase.getQuery() == null || evalCase.getQuery().isBlank()) {
            return false;
        }
        return !(isEmpty(evalCase.getRelevantSources()) && isEmpty(evalCase.getRelevantFileNames()));
    }

    private boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private void indexQuery(String query, RetrievalEvalDataset.RetrievalEvalCase evalCase) {
        String normalized = normalizeText(query);
        if (normalized.isEmpty()) {
            return;
        }

        RetrievalEvalDataset.RetrievalEvalCase previous = caseIndex.put(normalized, evalCase);
        if (previous != null && previous != evalCase) {
            logger.warn("检索评测集问法重复，后者覆盖前者: query='{}', oldCaseId={}, newCaseId={}",
                    query, previous.getId(), evalCase.getId());
        }
    }

    private boolean isRelevant(SearchResultMetadata metadata, Set<String> relevantSources, Set<String> relevantFileNames) {
        return (!metadata.source().isEmpty() && relevantSources.contains(metadata.source()))
                || (!metadata.fileName().isEmpty() && relevantFileNames.contains(metadata.fileName()));
    }

    private SearchResultMetadata extractMetadata(VectorSearchService.SearchResult result) {
        if (result == null || result.getMetadata() == null || result.getMetadata().isBlank()) {
            return new SearchResultMetadata("", "");
        }

        try {
            Map<String, Object> metadataMap = objectMapper.readValue(
                    result.getMetadata(),
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            String source = normalizeText(String.valueOf(metadataMap.getOrDefault("_source", "")));
            String fileName = normalizeText(String.valueOf(metadataMap.getOrDefault("_file_name", "")));
            return new SearchResultMetadata(source, fileName);
        } catch (Exception e) {
            logger.warn("解析检索结果 metadata 失败: {}", e.getMessage());
            return new SearchResultMetadata("", "");
        }
    }

    private Set<String> normalizeAll(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String item = normalizeText(value);
            if (!item.isEmpty()) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace("\\", "/")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private void writeEvalLog(Map<String, Object> logPayload) {
        try {
            evalLogger.info(objectMapper.writeValueAsString(logPayload));
        } catch (Exception e) {
            logger.warn("写入检索评估日志失败: {}", e.getMessage());
        }
    }

    private record SearchResultMetadata(String source, String fileName) {
    }
}
