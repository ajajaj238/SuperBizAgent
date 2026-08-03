package org.example.client;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 客户端工厂类
 * 负责创建和初始化 Milvus 客户端连接
 */
@Component
public class MilvusClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(MilvusClientFactory.class);

    @Autowired
    private MilvusProperties milvusProperties;

    /**
     * 创建并初始化 Milvus 客户端
     * 
     * 简化版本：直接连接并创建 collection
     * 
     * @return MilvusServiceClient 实例
     * @throws RuntimeException 如果连接或初始化失败
     */
    public MilvusServiceClient createClient() {
        MilvusServiceClient client = null;

        try {
            // 1. 连接到 Milvus
            logger.info("正在连接到 Milvus: {}:{}", milvusProperties.getHost(), milvusProperties.getPort());
            client = connectToMilvus();
            logger.info("成功连接到 Milvus");

            // 2. 检查并创建 biz collection（如果不存在）
            if (!collectionExists(client, MilvusConstants.MILVUS_COLLECTION_NAME)) {
                logger.info("collection '{}' 不存在，正在创建...", MilvusConstants.MILVUS_COLLECTION_NAME);
                createBizCollection(client);
                logger.info("成功创建 collection '{}'", MilvusConstants.MILVUS_COLLECTION_NAME);
                
                // 创建索引
                createIndexes(client);
                logger.info("成功创建索引");
            } else {
                logger.info("collection '{}' 已存在", MilvusConstants.MILVUS_COLLECTION_NAME);
            }

            initializeIntentExamplesCollection(client);
            initializeUserMemoriesCollection(client);
            initializeQaCacheCollection(client);

            return client;

        } catch (Exception e) {
            logger.error("创建 Milvus 客户端失败", e);
            if (client != null) {
                client.close();
            }
            throw new RuntimeException("创建 Milvus 客户端失败: " + e.getMessage(), e);
        }
    }

    /**
     * 连接到 Milvus
     */
    private MilvusServiceClient connectToMilvus() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(milvusProperties.getHost())
                .withPort(milvusProperties.getPort())
                .withConnectTimeout(milvusProperties.getTimeout(), TimeUnit.MILLISECONDS);

        // 如果配置了用户名和密码
        if (milvusProperties.getUsername() != null && !milvusProperties.getUsername().isEmpty()) {
            builder.withAuthorization(milvusProperties.getUsername(), milvusProperties.getPassword());
        }

        return new MilvusServiceClient(builder.build());
    }

    /**
     * 检查 collection 是否存在
     */
    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());

        if (response.getStatus() != 0) {
            throw new RuntimeException("检查 collection 失败: " + response.getMessage());
        }

        return response.getData();
    }

    /**
     * 创建 biz collection
     */
    private void createBizCollection(MilvusServiceClient client) {
        // 定义字段
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)  // 改为 FloatVector
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.JSON)
                .build();

        // 创建 collection schema
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(vectorField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .build();

        // 创建 collection
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withDescription("Business knowledge collection")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 collection 失败: " + response.getMessage());
        }
    }

    /**
     * 为 collection 创建索引
     */
    private void createIndexes(MilvusServiceClient client) {
        // 为 vector 字段创建索引（FloatVector 使用 IVF_FLAT 和 COSINE 相似度）
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)  // 余弦相似度
                .withExtraParam("{\"nlist\":68}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("创建 vector 索引失败: " + response.getMessage());
        }
        
        logger.info("成功为 vector 字段创建索引");
    }

    /**
     * 创建 intent_examples collection（方案 A）
     */
    private void createIntentExamplesCollection(MilvusServiceClient client) {
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(true)
                .build();

        FieldType intentField = FieldType.newBuilder()
                .withName("intent")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.INTENT_MAX_LENGTH)
                .build();

        FieldType exampleField = FieldType.newBuilder()
                .withName("example")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.INTENT_EXAMPLE_MAX_LENGTH)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(intentField)
                .addFieldType(exampleField)
                .addFieldType(embeddingField)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME)
                .withDescription("Intent seed examples collection")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            if (isAlreadyExistsError(response.getMessage())) {
                logger.info("intent_examples collection 已存在，跳过创建");
                return;
            }
            throw new RuntimeException("创建 intent_examples collection 失败: " + response.getMessage());
        }
    }

    /**
     * 为 intent_examples.embedding 创建索引
     */
    private void createIntentExamplesIndex(MilvusServiceClient client) {
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":68}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(indexParam);
        if (response.getStatus() != 0) {
            if (isAlreadyExistsError(response.getMessage())) {
                logger.info("intent_examples.embedding 索引已存在，跳过创建");
                return;
            }
            throw new RuntimeException("创建 intent_examples.embedding 索引失败: " + response.getMessage());
        }
    }

    /**
     * 意图识别 collection 是优化层：初始化失败不能阻塞主服务启动。
     * Milvus 刚启动时偶发 proxy 元数据刷新错误，重试后通常可恢复。
     */
    private void initializeIntentExamplesCollection(MilvusServiceClient client) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!collectionExists(client, MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME)) {
                    logger.info("collection '{}' 不存在，正在创建... attempt={}/{}",
                            MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME, attempt, maxAttempts);
                    createIntentExamplesCollection(client);
                    logger.info("成功创建 collection '{}'", MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME);
                } else {
                    logger.info("collection '{}' 已存在", MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME);
                }

                createIntentExamplesIndex(client);
                logger.info("成功创建或确认 intent_examples 索引");
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    logger.warn("intent_examples 初始化失败，应用将继续启动，意图识别会自动降级: {}", e.getMessage());
                    return;
                }
                logger.warn("intent_examples 初始化失败，准备重试: attempt={}/{}, error={}",
                        attempt, maxAttempts, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(2000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isAlreadyExistsError(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("already exist")
                || normalized.contains("already_exists")
                || normalized.contains("duplicated");
    }

    private void initializeUserMemoriesCollection(MilvusServiceClient client) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!collectionExists(client, MilvusConstants.USER_MEMORIES_COLLECTION_NAME)) {
                    logger.info("collection '{}' 不存在，正在创建... attempt={}/{}",
                            MilvusConstants.USER_MEMORIES_COLLECTION_NAME, attempt, maxAttempts);
                    createUserMemoriesCollection(client);
                    logger.info("成功创建 collection '{}'", MilvusConstants.USER_MEMORIES_COLLECTION_NAME);
                } else {
                    logger.info("collection '{}' 已存在", MilvusConstants.USER_MEMORIES_COLLECTION_NAME);
                }

                createUserMemoriesIndex(client);
                logger.info("成功创建或确认 user_memories 索引");
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    logger.warn("user_memories 初始化失败，应用将继续启动，长期语义记忆会自动降级: {}", e.getMessage());
                    return;
                }
                logger.warn("user_memories 初始化失败，准备重试: attempt={}/{}, error={}",
                        attempt, maxAttempts, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
    }

    private void createUserMemoriesCollection(MilvusServiceClient client) {
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType userIdField = FieldType.newBuilder()
                .withName("user_id")
                .withDataType(DataType.Int64)
                .build();

        FieldType sessionIdField = FieldType.newBuilder()
                .withName("session_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.SESSION_ID_MAX_LENGTH)
                .build();

        FieldType insightField = FieldType.newBuilder()
                .withName("insight")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.USER_MEMORY_MAX_LENGTH)
                .build();

        FieldType createdAtField = FieldType.newBuilder()
                .withName("created_at")
                .withDataType(DataType.Int64)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(userIdField)
                .addFieldType(sessionIdField)
                .addFieldType(insightField)
                .addFieldType(createdAtField)
                .addFieldType(embeddingField)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.USER_MEMORIES_COLLECTION_NAME)
                .withDescription("User semantic memory collection")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            if (isAlreadyExistsError(response.getMessage())) {
                logger.info("user_memories collection 已存在，跳过创建");
                return;
            }
            throw new RuntimeException("创建 user_memories collection 失败: " + response.getMessage());
        }
    }

    private void createUserMemoriesIndex(MilvusServiceClient client) {
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.USER_MEMORIES_COLLECTION_NAME)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(indexParam);
        if (response.getStatus() != 0) {
            if (isAlreadyExistsError(response.getMessage())) {
                logger.info("user_memories.embedding 索引已存在，跳过创建");
                return;
            }
            throw new RuntimeException("创建 user_memories.embedding 索引失败: " + response.getMessage());
        }
    }

    private void initializeQaCacheCollection(MilvusServiceClient client) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!collectionExists(client, MilvusConstants.QA_CACHE_COLLECTION_NAME)) {
                    logger.info("collection '{}' 不存在，正在创建... attempt={}/{}",
                            MilvusConstants.QA_CACHE_COLLECTION_NAME, attempt, maxAttempts);
                    createQaCacheCollection(client);
                    logger.info("成功创建 collection '{}'", MilvusConstants.QA_CACHE_COLLECTION_NAME);
                } else if (!hasCacheIdField(client)) {
                    // schema 升级：旧集合没有 cache_id 字段，删除重建（缓存数据可再生成）
                    logger.warn("qa_cache 集合缺少 cache_id 字段，删除重建以升级 schema（旧缓存数据作废，可自动重建）");
                    R<RpcStatus> drop = client.dropCollection(DropCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                            .build());
                    if (drop.getStatus() != 0) {
                        throw new RuntimeException("删除旧 qa_cache 集合失败: " + drop.getMessage());
                    }
                    createQaCacheCollection(client);
                    logger.info("qa_cache 集合已按新 schema 重建");
                } else {
                    logger.info("collection '{}' 已存在", MilvusConstants.QA_CACHE_COLLECTION_NAME);
                }

                createQaCacheIndex(client);
                logger.info("成功创建或确认 qa_cache 索引");
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    logger.warn("qa_cache 初始化失败，应用将继续启动，答案缓存会自动降级: {}", e.getMessage());
                    return;
                }
                logger.warn("qa_cache 初始化失败，准备重试: attempt={}/{}, error={}",
                        attempt, maxAttempts, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
    }

    /**
     * 检查 qa_cache 集合是否包含 cache_id 字段（区分新旧 schema）
     */
    private boolean hasCacheIdField(MilvusServiceClient client) {
        try {
            R<DescribeCollectionResponse> response = client.describeCollection(
                    DescribeCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                            .build());
            if (response.getStatus() != 0 || response.getData() == null) {
                logger.warn("describe qa_cache 失败，按最新 schema 处理: {}", response.getMessage());
                return true;
            }
            for (FieldSchema field : response.getData().getSchema().getFieldsList()) {
                if ("cache_id".equals(field.getName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            logger.warn("describe qa_cache 异常，按最新 schema 处理: {}", e.getMessage());
            return true;
        }
    }

    private void createQaCacheCollection(MilvusServiceClient client) {
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.QA_CACHE_KEY_MAX_LENGTH)
                .withPrimaryKey(true)
                .build();

        FieldType questionField = FieldType.newBuilder()
                .withName("question")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.QA_CACHE_QUESTION_MAX_LENGTH)
                .build();

        FieldType cacheIdField = FieldType.newBuilder()
                .withName("cache_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(MilvusConstants.QA_CACHE_KEY_MAX_LENGTH)
                .build();

        FieldType hitCountField = FieldType.newBuilder()
                .withName("hit_count")
                .withDataType(DataType.Int64)
                .build();

        FieldType createdAtField = FieldType.newBuilder()
                .withName("created_at")
                .withDataType(DataType.Int64)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(MilvusConstants.VECTOR_DIM)
                .build();

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .addFieldType(idField)
                .addFieldType(questionField)
                .addFieldType(cacheIdField)
                .addFieldType(hitCountField)
                .addFieldType(createdAtField)
                .addFieldType(embeddingField)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                .withDescription("QA answer cache question collection")
                .withSchema(schema)
                .withShardsNum(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build();

        R<RpcStatus> response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            if (isAlreadyExistsError(response.getMessage())) {
                logger.info("qa_cache collection 已存在，跳过创建");
                return;
            }
            throw new RuntimeException("创建 qa_cache collection 失败: " + response.getMessage());
        }
    }

    private void createQaCacheIndex(MilvusServiceClient client) {
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":68}")
                .withSyncMode(Boolean.FALSE)
                .build();

        R<RpcStatus> response = client.createIndex(indexParam);
        if (response.getStatus() != 0) {
            if (isAlreadyExistsError(response.getMessage())) {
                logger.info("qa_cache.embedding 索引已存在，跳过创建");
                return;
            }
            throw new RuntimeException("创建 qa_cache.embedding 索引失败: " + response.getMessage());
        }
    }
}
