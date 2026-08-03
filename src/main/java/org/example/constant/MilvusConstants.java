package org.example.constant;

public class MilvusConstants {
    
    /**
     * Milvus 数据库名称
     */
    public static final String MILVUS_DB_NAME = "default";
    
    /**
     * Milvus 集合名称
     */
    public static final String MILVUS_COLLECTION_NAME = "biz";

    /**
     * 意图示例集合名称（独立 collection，避免与 RAG 文档混存）
     */
    public static final String INTENT_EXAMPLES_COLLECTION_NAME = "intent_examples";

    /**
     * 用户长期语义记忆集合
     */
    public static final String USER_MEMORIES_COLLECTION_NAME = "user_memories";

    /**
     * QA 答案缓存问题集合（分离式缓存：问题存 Milvus，答案存 Redis）
     */
    public static final String QA_CACHE_COLLECTION_NAME = "qa_cache";
    
    /**
     * 向量维度（豆包 embedding 模型的维度）
     */
    public static final int VECTOR_DIM = 1024;  // 返回1024维向量
    
    /**
     * ID字段最大长度
     */
    public static final int ID_MAX_LENGTH = 256;
    
    /**
     * Content字段最大长度
     */
    public static final int CONTENT_MAX_LENGTH = 8192;

    /**
     * 意图字段最大长度
     */
    public static final int INTENT_MAX_LENGTH = 32;

    /**
     * 意图示例文本最大长度
     */
    public static final int INTENT_EXAMPLE_MAX_LENGTH = 512;

    /**
     * 会话ID字段最大长度
     */
    public static final int SESSION_ID_MAX_LENGTH = 64;

    /**
     * 用户语义记忆文本最大长度
     */
    public static final int USER_MEMORY_MAX_LENGTH = 2048;

    /**
     * 缓存问题文本最大长度
     */
    public static final int QA_CACHE_QUESTION_MAX_LENGTH = 512;

    /**
     * 缓存键（问题 MD5）最大长度
     */
    public static final int QA_CACHE_KEY_MAX_LENGTH = 64;
    
    /**
     * 默认分片数
     */
    public static final int DEFAULT_SHARD_NUMBER = 2;
    
    private MilvusConstants() {
        // 工具类，禁止实例化
    }
}
