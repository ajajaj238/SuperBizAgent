package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * QA 答案缓存配置。
 * 分离式架构：Milvus 存问题（语义检索），Redis 存答案。
 */
@ConfigurationProperties(prefix = "qa-cache")
public class QaCacheProperties {

    /** 总开关 */
    private boolean enabled = true;

    /**
     * Milvus 语义检索召回阈值（COSINE 相似度）。
     * 实测 text-embedding-v4 上同义改写与跨主题相似度重叠在 0.85 左右，
     * 单靠余弦阈值无法兼顾召回与精确，因此默认降到 0.80 做召回，
     * 由 subject-check 做主题一致性校验来保证精确。
     */
    private double similarityThreshold = 0.80;

    /**
     * Milvus 候选召回阈值（宽松），低于该分数的候选直接丢弃。
     * 精确判定交给 reranker。
     */
    private double recallThreshold = 0.70;

    /**
     * 是否在写侧做"答案相似度校验"后再归并到已有 cluster（防止归并错误答案）
     */
    private boolean mergeAnswerCheckEnabled = true;

    /**
     * 答案相似度归并阈值（新 LLM 答案 vs 缓存答案的余弦相似度）
     */
    private double answerSimilarityThreshold = 0.90;

    /** 是否启用主题一致性校验（防止"内存飙高怎么办"命中"CPU飙高怎么办"） */
    private boolean subjectCheckEnabled = true;

    /**
     * 是否启用诉求类型一致性校验：
     * 防止"cpu太高是什么原因"与"cpu太高怎么解决"这类同主题但诉求不同的问题共用缓存
     */
    private boolean askTypeCheckEnabled = true;

    /** 领域主题词表（CJK 主题词；CPU/Redis 等字母词自动作为主题词） */
    private List<String> subjectWords = new ArrayList<>(List.of(
            "内存", "磁盘", "硬盘", "文件", "日志", "服务", "数据库", "网络", "带宽",
            "网关", "接口", "线程", "连接池", "连接", "缓存", "堆", "容器", "集群",
            "节点", "应用", "进程", "队列", "消息", "索引", "监控", "告警", "阈值",
            "规则", "时区", "订单", "支付", "链路", "熔断", "限流", "雪崩", "故障",
            "性能", "资源", "负载", "请求", "响应", "大模型", "向量", "召回", "会话",
            "用户", "页面", "前端", "后端", "中间件", "存储", "备份", "归档"
    ));

    /** Redis 答案缓存 TTL，默认 7 天；文档变更时另行主动失效 */
    private Duration redisTtl = Duration.ofDays(7);

    /** 最短问题长度（字符），太短不缓存 */
    private int minQuestionLength = 4;

    /** 最长问题长度（字符），太长不缓存 */
    private int maxQuestionLength = 200;

    /** 动态/时效问题关键词，命中即不查缓存、不落缓存 */
    private List<String> dynamicKeywords = new ArrayList<>(List.of(
            "现在", "当前", "目前", "今天", "昨天", "最近", "实时",
            "几点", "几号", "星期", "什么时间", "目前时间",
            "告警", "指标", "日志", "状态", "曲线", "趋势",
            "是多少", "多少了", "有多高", "有多少", "有没有", "是否有",
            "查一下", "帮我查", "查询一下", "拉取", "统计一下"
    ));

    /** 动态/时效问题正则，命中即不查缓存 */
    private List<String> dynamicPatterns = new ArrayList<>(List.of(
            "最近\\d+[分钟小时天周月]",
            "近\\d+[分钟小时天周月]",
            "\\d{1,2}:\\d{2}",
            "\\d{4}[-年/]\\d{1,2}[-月/]?\\d{0,2}"
    ));

    /** 语义检索路径上是否用 embedding 意图做二次闸门（防动态问题误命中） */
    private boolean intentCheckEnabled = true;

    /** embedding 意图二次闸门的相似度阈值 */
    private double intentThreshold = 0.85;

    /** 是否异步落缓存（不阻塞首次回答） */
    private boolean asyncSave = true;

    /** 缓存答案最大长度，过长不缓存 */
    private int maxAnswerLength = 8000;

    /** 重排序（rerank）配置 */
    private Rerank rerank = new Rerank();

    /** 离线阈值评测配置 */
    private Evaluation evaluation = new Evaluation();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public boolean isSubjectCheckEnabled() {
        return subjectCheckEnabled;
    }

    public void setSubjectCheckEnabled(boolean subjectCheckEnabled) {
        this.subjectCheckEnabled = subjectCheckEnabled;
    }

    public List<String> getSubjectWords() {
        return subjectWords;
    }

    public void setSubjectWords(List<String> subjectWords) {
        this.subjectWords = subjectWords;
    }

    public boolean isAskTypeCheckEnabled() {
        return askTypeCheckEnabled;
    }

    public void setAskTypeCheckEnabled(boolean askTypeCheckEnabled) {
        this.askTypeCheckEnabled = askTypeCheckEnabled;
    }

    public Duration getRedisTtl() {
        return redisTtl;
    }

    public void setRedisTtl(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }

    public int getMinQuestionLength() {
        return minQuestionLength;
    }

    public void setMinQuestionLength(int minQuestionLength) {
        this.minQuestionLength = minQuestionLength;
    }

    public int getMaxQuestionLength() {
        return maxQuestionLength;
    }

    public void setMaxQuestionLength(int maxQuestionLength) {
        this.maxQuestionLength = maxQuestionLength;
    }

    public List<String> getDynamicKeywords() {
        return dynamicKeywords;
    }

    public void setDynamicKeywords(List<String> dynamicKeywords) {
        this.dynamicKeywords = dynamicKeywords;
    }

    public List<String> getDynamicPatterns() {
        return dynamicPatterns;
    }

    public void setDynamicPatterns(List<String> dynamicPatterns) {
        this.dynamicPatterns = dynamicPatterns;
    }

    public boolean isIntentCheckEnabled() {
        return intentCheckEnabled;
    }

    public void setIntentCheckEnabled(boolean intentCheckEnabled) {
        this.intentCheckEnabled = intentCheckEnabled;
    }

    public double getIntentThreshold() {
        return intentThreshold;
    }

    public void setIntentThreshold(double intentThreshold) {
        this.intentThreshold = intentThreshold;
    }

    public boolean isAsyncSave() {
        return asyncSave;
    }

    public void setAsyncSave(boolean asyncSave) {
        this.asyncSave = asyncSave;
    }

    public int getMaxAnswerLength() {
        return maxAnswerLength;
    }

    public void setMaxAnswerLength(int maxAnswerLength) {
        this.maxAnswerLength = maxAnswerLength;
    }

    public double getRecallThreshold() {
        return recallThreshold;
    }

    public void setRecallThreshold(double recallThreshold) {
        this.recallThreshold = recallThreshold;
    }

    public boolean isMergeAnswerCheckEnabled() {
        return mergeAnswerCheckEnabled;
    }

    public void setMergeAnswerCheckEnabled(boolean mergeAnswerCheckEnabled) {
        this.mergeAnswerCheckEnabled = mergeAnswerCheckEnabled;
    }

    public double getAnswerSimilarityThreshold() {
        return answerSimilarityThreshold;
    }

    public void setAnswerSimilarityThreshold(double answerSimilarityThreshold) {
        this.answerSimilarityThreshold = answerSimilarityThreshold;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public void setRerank(Rerank rerank) {
        this.rerank = rerank;
    }

    public static class Rerank {
        /** 是否启用 rerank 精排（关闭时降级为余弦+主题校验） */
        private boolean enabled = true;

        /** 百炼文本排序模型（实测 qwen3-rerank 需配合 instruct 使用） */
        private String model = "qwen3-rerank";

        /** 百炼文本排序 API 地址（已验证可用） */
        private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

        /** 排序任务说明：语义等价（FAQ 场景），实测可显著拉开边界问题分差 */
        private String instruct = "Retrieve semantically similar text.";

        /** rerank 命中阈值（relevance_score），建议用评测集校准 */
        private double threshold = 0.72;

        /** 单次参与精排的候选数上限 */
        private int topN = 5;

        /** 超时（毫秒），超时/异常时降级为余弦+主题校验 */
        private int timeoutMs = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getInstruct() {
            return instruct;
        }

        public void setInstruct(String instruct) {
            this.instruct = instruct;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public Evaluation getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(Evaluation evaluation) {
        this.evaluation = evaluation;
    }

    public static class Evaluation {
        /** 启动时是否运行缓存阈值离线评测 */
        private boolean enabled = false;

        private String datasetLocation = "classpath:qa-cache-eval.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDatasetLocation() {
            return datasetLocation;
        }

        public void setDatasetLocation(String datasetLocation) {
            this.datasetLocation = datasetLocation;
        }
    }
}
