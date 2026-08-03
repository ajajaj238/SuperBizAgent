package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼文本排序（rerank）客户端。
 * 使用 qwen3-rerank 模型（已验证 legacy 端点 + instruct 参数可用）。
 * cross-encoder 联合编码 query+document，可显著区分"内存太高怎么办"与"CPU飙高怎么办"这类边界问题。
 */
@Service
public class RerankService {

    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${qa-cache.rerank.base-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String baseUrl;

    @Value("${qa-cache.rerank.model:qwen3-rerank}")
    private String model;

    @Value("${qa-cache.rerank.instruct:Retrieve semantically similar text.}")
    private String instruct;

    @Value("${qa-cache.rerank.top-n:5}")
    private int topN;

    @Value("${qa-cache.rerank.timeout-ms:5000}")
    private int timeoutMs;

    public RerankService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 对候选文档精排，返回按 relevance_score 降序的结果。
     *
     * @param query     用户问题
     * @param documents 候选问题文本（通常来自 Milvus 召回）
     * @return 排序结果（index 对应 documents 下标）
     */
    public List<RerankScore> rerank(String query, List<String> documents) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("instruct", instruct);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("rerank HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode results = root.path("output").path("results");
        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode item : results) {
            scores.add(new RerankScore(item.path("index").asInt(-1), item.path("relevance_score").asDouble(-1.0)));
        }
        scores.sort(Comparator.comparingDouble(RerankScore::score).reversed());
        logger.debug("rerank 完成: query='{}', candidates={}, results={}",
                query, documents.size(), scores);
        return scores;
    }

    public record RerankScore(int index, double score) {
    }
}
