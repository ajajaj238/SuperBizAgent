package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 检索结果重排服务
 * 使用向量分数 + 关键词覆盖率做融合评分，并按阈值过滤低相关内容。
 */
@Service
public class RagRerankService {

    private static final Logger logger = LoggerFactory.getLogger(RagRerankService.class);
    private static final Pattern ALNUM_PATTERN = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${rag.rerank.keyword-weight:0.3}")
    private double keywordWeight;

    @Value("${rag.rerank.min-relevance-score:0.22}")
    private double minRelevanceScore;

    /**
     * 对候选结果进行重排和过滤。
     *
     * @param query 用户查询
     * @param candidates 候选文档（向量检索结果）
     * @param finalTopK 最终保留数量
     * @return 重排后结果
     */
    public List<VectorSearchService.SearchResult> rerankAndFilter(
            String query,
            List<VectorSearchService.SearchResult> candidates,
            int finalTopK) {

        if (!rerankEnabled || candidates == null || candidates.isEmpty()) {
            return limit(candidates, finalTopK);
        }

        Set<String> queryTokens = tokenize(query);
        List<VectorSearchService.SearchResult> scored = new ArrayList<>(candidates);

        for (VectorSearchService.SearchResult item : scored) {
            double vectorSim = toSimilarity(item.getScore());
            item.setVectorSimilarity((float) vectorSim);
        }

        for (VectorSearchService.SearchResult item : scored) {
            double vectorNorm = safe(item.getVectorSimilarity());
            double keywordCoverage = keywordCoverage(queryTokens, item.getContent());
            double fused = vectorWeight * vectorNorm + keywordWeight * keywordCoverage;

            item.setKeywordScore((float) keywordCoverage);
            item.setRerankScore((float) fused);
        }

        scored.sort(Comparator.comparing(
                (VectorSearchService.SearchResult v) -> safe(v.getRerankScore()))
                .reversed());

        List<VectorSearchService.SearchResult> filtered = new ArrayList<>();
        for (VectorSearchService.SearchResult item : scored) {
            if (safe(item.getRerankScore()) >= minRelevanceScore) {
                filtered.add(item);
            }
        }

        List<VectorSearchService.SearchResult> limited = limit(filtered, finalTopK);
        logger.info("RAG rerank 完成: candidates={}, filtered={}, returned={}, threshold={}",
                candidates.size(), filtered.size(), limited.size(), minRelevanceScore);
        return limited;
    }

    /**
     * 是否启用 rerank。
     */
    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    private List<VectorSearchService.SearchResult> limit(List<VectorSearchService.SearchResult> input, int finalTopK) {
        if (input == null || input.isEmpty() || finalTopK <= 0) {
            return new ArrayList<>();
        }
        if (input.size() <= finalTopK) {
            return new ArrayList<>(input);
        }
        return new ArrayList<>(input.subList(0, finalTopK));
    }

    private double toSimilarity(float score) {
        // Milvus COSINE 分数通常在 [-1, 1]，映射到 [0, 1] 便于与关键词分数融合。
        double normalized = (score + 1.0) / 2.0;
        if (normalized < 0.0) {
            return 0.0;
        }
        if (normalized > 1.0) {
            return 1.0;
        }
        return normalized;
    }

    private double keywordCoverage(Set<String> queryTokens, String content) {
        if (queryTokens == null || queryTokens.isEmpty() || content == null || content.isBlank()) {
            return 0.0;
        }
        Set<String> docTokens = tokenize(content);
        if (docTokens.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String token : queryTokens) {
            if (docTokens.contains(token)) {
                hit++;
            }
        }
        return (double) hit / queryTokens.size();
    }


    /**
     * 分词。
     *
     * @param text 输入文本
     * @return 分词结果
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        String normalized = text.toLowerCase(Locale.ROOT);

        Matcher alnumMatcher = ALNUM_PATTERN.matcher(normalized);
        while (alnumMatcher.find()) {
            String token = alnumMatcher.group();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }

        Matcher cjkMatcher = CJK_PATTERN.matcher(normalized);
        while (cjkMatcher.find()) {
            tokens.add(cjkMatcher.group());
        }
        return tokens;
    }

    private float safe(Float value) {
        return value == null ? 0.0f : value;
    }
}
