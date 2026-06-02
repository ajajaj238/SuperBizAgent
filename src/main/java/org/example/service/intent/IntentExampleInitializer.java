package org.example.service.intent;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.dml.InsertParam;
import org.example.constant.MilvusConstants;
import org.example.service.VectorEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class IntentExampleInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(IntentExampleInitializer.class);

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;

    public IntentExampleInitializer(MilvusServiceClient milvusClient, VectorEmbeddingService embeddingService) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            long rowCount = getIntentExampleRowCount();
            if (rowCount > 0) {
                logger.info("intent_examples 已有 {} 条数据，跳过种子初始化", rowCount);
                return;
            }

            List<IntentExample> examples = seedExamples();
            insertExamples(examples);
            logger.info("intent_examples 种子初始化完成，写入 {} 条示例", examples.size());
        } catch (Exception e) {
            logger.warn("intent_examples 种子初始化失败，意图识别将自动降级: {}", e.getMessage());
        }
    }

    private long getIntentExampleRowCount() {
        R<GetCollectionStatisticsResponse> response = milvusClient.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder()
                        .withCollectionName(MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME)
                        .withFlush(Boolean.TRUE)
                        .build()
        );
        if (response.getStatus() != 0 || response.getData() == null) {
            logger.warn("读取 intent_examples 统计失败: {}", response.getMessage());
            return 0;
        }

        for (KeyValuePair stat : response.getData().getStatsList()) {
            if ("row_count".equals(stat.getKey())) {
                return Long.parseLong(stat.getValue());
            }
        }
        return 0;
    }

    private void insertExamples(List<IntentExample> examples) {
        List<String> texts = examples.stream().map(IntentExample::example).toList();
        List<List<Float>> embeddings = embeddingService.generateEmbeddings(texts);
        if (embeddings.size() != examples.size()) {
            throw new IllegalStateException("意图示例向量数量不匹配");
        }

        List<String> intents = new ArrayList<>();
        List<String> exampleTexts = new ArrayList<>();
        for (IntentExample example : examples) {
            intents.add(example.intent().name());
            exampleTexts.add(example.example());
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("intent", intents));
        fields.add(new InsertParam.Field("example", exampleTexts));
        fields.add(new InsertParam.Field("embedding", embeddings));

        R<MutationResult> response = milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.INTENT_EXAMPLES_COLLECTION_NAME)
                .withFields(fields)
                .build());
        if (response.getStatus() != 0) {
            throw new RuntimeException("写入 intent_examples 失败: " + response.getMessage());
        }
    }

    private List<IntentExample> seedExamples() {
        Map<UserIntent, List<String>> seeds = Map.of(
                UserIntent.KNOWLEDGE_QA, List.of(
                        "CPU 使用率过高怎么处理",
                        "磁盘满了怎么办",
                        "服务不可用排查步骤",
                        "慢响应问题的最佳实践",
                        "内存泄漏如何排查",
                        "SOP 在哪里查看"),
                UserIntent.ALERT_DIAGNOSIS, List.of(
                        "分析一下当前告警",
                        "帮我看看系统有什么问题",
                        "检查所有活跃告警",
                        "自动诊断一下",
                        "告警分析报告",
                        "运维巡检"),
                UserIntent.LOG_QUERY, List.of(
                        "查一下最近一小时的 error 日志",
                        "查询今天的日志",
                        "看看有没有报错",
                        "日志里有什么异常",
                        "检查应用日志"),
                UserIntent.METRICS_QUERY, List.of(
                        "当前有哪些告警",
                        "CPU 使用率是多少",
                        "内存占用情况",
                        "查看监控指标",
                        "Prometheus 有什么数据"),
                UserIntent.TIME_QUERY, List.of(
                        "现在几点",
                        "今天几号",
                        "星期几",
                        "当前时间"),
                UserIntent.CHITCHAT, List.of(
                        "你好",
                        "你是谁",
                        "你能做什么",
                        "介绍一下你自己",
                        "谢谢",
                        "再见"),
                UserIntent.SYSTEM_OPERATION, List.of(
                        "清空历史记录",
                        "删除会话",
                        "开始新对话",
                        "重置")
        );

        List<IntentExample> examples = new ArrayList<>();
        for (Map.Entry<UserIntent, List<String>> entry : seeds.entrySet()) {
            for (String example : entry.getValue()) {
                examples.add(new IntentExample(entry.getKey(), example));
            }
        }
        return Collections.unmodifiableList(examples);
    }
}
