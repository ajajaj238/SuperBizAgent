package org.example.service.session;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.example.constant.MilvusConstants;
import org.example.service.VectorEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserMemoryVectorStore {

    private static final Logger logger = LoggerFactory.getLogger(UserMemoryVectorStore.class);

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;

    public UserMemoryVectorStore(MilvusServiceClient milvusClient, VectorEmbeddingService embeddingService) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
    }

    public void storeSessionSummary(Long userId, String sessionId, String summary) {
        if (userId == null || sessionId == null || sessionId.isBlank() || summary == null || summary.isBlank()) {
            return;
        }

        ensureCollectionLoaded();
        clearSessionMemories(userId, sessionId);
        List<Float> embedding = embeddingService.generateEmbedding(summary);
        String id = UUID.nameUUIDFromBytes((userId + ":" + sessionId + ":" + summary).getBytes(StandardCharsets.UTF_8)).toString();

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("user_id", Collections.singletonList(userId)));
        fields.add(new InsertParam.Field("session_id", Collections.singletonList(sessionId)));
        fields.add(new InsertParam.Field("insight", Collections.singletonList(summary)));
        fields.add(new InsertParam.Field("created_at", Collections.singletonList(System.currentTimeMillis())));
        fields.add(new InsertParam.Field("embedding", Collections.singletonList(embedding)));

        R<MutationResult> response = milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.USER_MEMORIES_COLLECTION_NAME)
                .withFields(fields)
                .build());
        if (response.getStatus() != 0) {
            throw new RuntimeException("写入 user_memories 失败: " + response.getMessage());
        }
    }

    /**
     * 按 sessionId 查询单条会话摘要（精准查询）
     */
    public Optional<String> getSessionSummary(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        ensureCollectionLoaded();
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.USER_MEMORIES_COLLECTION_NAME)
                .withVectorFieldName("embedding")
                .withVectors(Collections.singletonList(Collections.nCopies(
                        MilvusConstants.VECTOR_DIM, 0.0f)))
                .withTopK(1)
                .withExpr("user_id == " + userId + " and session_id == \"" + sessionId + "\"")
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withOutFields(List.of("insight"))
                .build();

        R<SearchResults> response = milvusClient.search(searchParam);
        if (response.getStatus() != 0) {
            return Optional.empty();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        int rowCount = wrapper.getRowRecords(0).size();
        if (rowCount == 0) {
            return Optional.empty();
        }
        Object insight = wrapper.getFieldData("insight", 0).get(0);
        return Optional.ofNullable(insight).map(Object::toString);
    }

    public List<String> searchRelevantMemories(Long userId, String sessionId, String currentQuestion, int topK) {
        if (userId == null || sessionId == null || sessionId.isBlank() || currentQuestion == null || currentQuestion.isBlank()) {
            return List.of();
        }

        ensureCollectionLoaded();
        List<Float> queryVector = embeddingService.generateQueryVector(currentQuestion);
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.USER_MEMORIES_COLLECTION_NAME)
                .withVectorFieldName("embedding")
                .withVectors(Collections.singletonList(queryVector))
                .withTopK(topK)
                .withExpr("user_id == " + userId + " and session_id == \"" + sessionId + "\"")
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withOutFields(List.of("session_id", "insight", "created_at"))
                .withParams("{\"nprobe\":10}")
                .build();

        R<SearchResults> searchResponse = milvusClient.search(searchParam);
        if (searchResponse.getStatus() != 0) {
            throw new RuntimeException("搜索 user_memories 失败: " + searchResponse.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
        List<String> memories = new ArrayList<>();
        int rowCount = wrapper.getRowRecords(0).size();
        for (int i = 0; i < rowCount; i++) {
            Object insight = wrapper.getFieldData("insight", 0).get(i);
            if (insight != null && !insight.toString().isBlank()) {
                memories.add(insight.toString());
            }
        }
        return memories;
    }

    public void clearSessionMemories(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ensureCollectionLoaded();
        String expr = "user_id == " + userId + " and session_id == \"" + sessionId + "\"";
        R<MutationResult> response = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.USER_MEMORIES_COLLECTION_NAME)
                .withExpr(expr)
                .build());
        if (response.getStatus() != 0) {
            logger.warn("删除 user_memories 中的会话摘要失败: {}", response.getMessage());
        }
    }

    private void ensureCollectionLoaded() {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.USER_MEMORIES_COLLECTION_NAME)
                        .build()
        );
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载 user_memories collection 失败: " + loadResponse.getMessage());
        }
    }
}
