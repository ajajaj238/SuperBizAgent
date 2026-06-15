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
import java.util.LinkedHashMap;
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
            List<IntentExample> examples = seedExamples();
            long rowCount = getIntentExampleRowCount();
            if (rowCount >= examples.size()) {
                logger.info("intent_examples 已有 {} 条数据，当前种子样例 {} 条，无需补充", rowCount, examples.size());
                return;
            }

            List<IntentExample> missingExamples = examples.subList((int) Math.max(0, rowCount), examples.size());
            insertExamples(missingExamples);
            logger.info("intent_examples 种子补充完成，已有 {} 条，本次写入 {} 条，目标总数 {} 条",
                    rowCount, missingExamples.size(), examples.size());
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
        Map<UserIntent, List<String>> seeds = new LinkedHashMap<>();
        seeds.put(UserIntent.KNOWLEDGE_QA, List.of(
                "CPU 使用率过高怎么处理",
                "磁盘满了怎么办",
                "服务不可用排查步骤",
                "慢响应问题的最佳实践",
                "内存泄漏如何排查",
                "SOP 在哪里查看",
                "CPU 使用率过高一般是什么原因",
                "CPU 飙高有哪些排查思路",
                "Java 应用 CPU 打满怎么定位",
                "线程数过多会导致 CPU 高吗",
                "内存使用率过高怎么处理",
                "JVM 内存持续上涨怎么排查",
                "频繁 Full GC 的原因有哪些",
                "堆内存溢出怎么定位",
                "磁盘使用率过高一般是什么原因导致的",
                "磁盘空间不足有哪些清理方案",
                "日志文件太大怎么治理",
                "inode 满了怎么排查",
                "服务响应慢怎么排查",
                "接口超时一般有哪些原因",
                "服务不可用通常怎么处理",
                "503 错误怎么排查",
                "数据库慢查询怎么优化",
                "连接池耗尽怎么排查",
                "Redis 连接超时怎么处理",
                "Kubernetes Pod 一直重启怎么排查",
                "Pod OOMKilled 怎么处理",
                "容器 CrashLoopBackOff 怎么解决",
                "网关 502 怎么定位",
                "支付服务异常的排查流程",
                "订单服务延迟高的处理方案",
                "Prometheus 告警规则怎么配置",
                "告警阈值应该怎么设置",
                "日志采集最佳实践",
                "链路追踪怎么排查慢请求",
                "怎么设计运维故障处理 SOP",
                "高可用系统有哪些最佳实践",
                "微服务雪崩怎么防止",
                "熔断降级怎么做",
                "限流策略怎么设计",
                "如何优化接口 P95 延迟",
                "如何降低大模型接口延迟",
                "RAG 检索不准怎么优化",
                "向量检索召回率怎么提升",
                "Milvus 查询慢怎么排查",
                "MySQL 索引失效怎么处理",
                "数据库连接数打满怎么办",
                "线上故障复盘应该包含哪些内容",
                "监控指标体系怎么建设",
                "告警风暴怎么治理"));

        seeds.put(UserIntent.ALERT_DIAGNOSIS, List.of(
                "分析一下当前告警",
                "帮我看看系统有什么问题",
                "检查所有活跃告警",
                "自动诊断一下",
                "告警分析报告",
                "运维巡检",
                "帮我分析当前系统告警并生成诊断报告",
                "看一下现在有没有严重告警",
                "诊断一下当前服务异常",
                "帮我做一次 AIOps 分析",
                "当前告警的根因是什么",
                "根据告警生成根因分析",
                "给我一份故障诊断报告",
                "分析这些告警之间有没有关联",
                "帮我判断是不是雪崩故障",
                "检查系统健康状态并总结风险",
                "当前集群有什么异常需要处理",
                "自动排查一下线上故障",
                "帮我定位这次故障原因",
                "对当前监控告警做综合分析",
                "巡检一下核心服务状态",
                "生成一份运维巡检结论",
                "分析 payment-service 告警",
                "分析 order-service 当前异常",
                "看一下告警是不是误报",
                "帮我判断告警优先级",
                "从告警日志指标里找根因",
                "当前系统是否存在静默故障",
                "结合日志和指标给出处理建议",
                "输出告警影响范围和修复方案",
                "生成 P1 故障分析报告",
                "给出当前故障的止血方案",
                "分析最近告警趋势",
                "排查当前生产环境异常",
                "帮我做故障定位"));

        seeds.put(UserIntent.LOG_QUERY, List.of(
                "查一下最近一小时的 error 日志",
                "查询今天的日志",
                "看看有没有报错",
                "日志里有什么异常",
                "检查应用日志",
                "帮我查一下 payment-service 的错误日志",
                "查询 order-service 最近半小时日志",
                "看一下 user-service 有没有异常堆栈",
                "检索最近 10 分钟 error",
                "查 WARN 日志",
                "查询 fatal 日志",
                "帮我找一下超时相关日志",
                "查一下 Connection timeout 日志",
                "查询 OOM 相关日志",
                "查一下 NullPointerException",
                "看下接口 /api/order 的日志",
                "查 traceId 为 abc123 的日志",
                "根据订单号查日志",
                "查询某个用户请求日志",
                "最近有没有登录失败日志",
                "查一下网关访问日志",
                "看一下 nginx 错误日志",
                "查询 ap-guangzhou 地域日志",
                "查最近一天的应用日志",
                "帮我拉取这段时间的异常日志",
                "日志里有没有数据库连接失败",
                "查一下慢请求日志",
                "查询服务启动失败日志",
                "查最近的报错堆栈",
                "帮我过滤包含 timeout 的日志",
                "统计最近错误日志数量",
                "看看日志有没有重试风暴",
                "查 CLS 日志",
                "调用日志工具查询异常",
                "帮我查生产日志"));

        seeds.put(UserIntent.METRICS_QUERY, List.of(
                "当前有哪些告警",
                "CPU 使用率是多少",
                "内存占用情况",
                "查看监控指标",
                "Prometheus 有什么数据",
                "查一下当前 Prometheus 告警",
                "看一下 firing 告警",
                "当前 pending 告警有哪些",
                "查询服务 CPU 指标",
                "查询内存使用率",
                "查磁盘使用率",
                "查看网络流量指标",
                "当前 QPS 是多少",
                "接口 P95 延迟是多少",
                "查看接口错误率",
                "查 5xx 错误率",
                "数据库连接池使用率是多少",
                "查看 JVM 堆内存指标",
                "查询 GC 次数",
                "Full GC 频率是多少",
                "Pod 当前重启次数",
                "Kubernetes 节点资源使用率",
                "查看 payment-service 监控",
                "查询 order-service 指标",
                "看一下 user-service 延迟指标",
                "查最近一小时 CPU 曲线",
                "查看服务可用性指标",
                "当前告警状态怎么样",
                "查询监控面板数据",
                "拉一下实时指标",
                "现在系统负载是多少",
                "Prometheus 查询当前资源使用率",
                "查看容器内存指标",
                "查服务实例健康状态",
                "看一下监控有没有异常"));

        seeds.put(UserIntent.TIME_QUERY, List.of(
                "现在几点",
                "今天几号",
                "星期几",
                "当前时间",
                "现在是什么时间",
                "当前日期是多少",
                "今天是周几",
                "现在北京时间几点",
                "现在上海时间",
                "给我当前时间",
                "告诉我今天日期",
                "现在是几月几号",
                "今天是哪一天",
                "当前时间戳是多少",
                "现在的年月日",
                "此刻时间",
                "今天日期和星期",
                "现在几点了",
                "今天周几",
                "现在时间是多少"));

        seeds.put(UserIntent.CHITCHAT, List.of(
                "你好",
                "你好，我是王杰",
                "你是谁",
                "你能做什么",
                "介绍一下你自己",
                "谢谢",
                "再见",
                "早上好",
                "晚上好",
                "在吗",
                "哈喽",
                "hi",
                "hello",
                "辛苦了",
                "感谢你的帮助",
                "多谢",
                "拜拜",
                "回头聊",
                "你叫什么名字",
                "你是哪个模型",
                "你有什么能力",
                "你可以帮我做什么",
                "我是谁",
                "你知道我是谁吗",
                "我是张三",
                "我叫李雷",
                "我的名字是 Jay",
                "记住我是运维工程师",
                "我想随便聊聊",
                "讲个笑话",
                "你真厉害",
                "好的",
                "嗯嗯",
                "明白了",
                "没事了",
                "先这样",
                "继续聊",
                "你还在吗",
                "测试一下",
                "随便问问"));

        seeds.put(UserIntent.SYSTEM_OPERATION, List.of(
                "清空历史记录",
                "删除会话",
                "开始新对话",
                "重置",
                "清空当前会话",
                "清除上下文",
                "把聊天记录删掉",
                "删除当前聊天",
                "删掉这个会话",
                "新建一个会话",
                "开启新的对话",
                "重新开始",
                "重置会话",
                "清理记忆",
                "忘记刚才的内容",
                "清空对话上下文",
                "把这个 session 清掉",
                "结束当前会话",
                "重新开一轮",
                "不要保留历史",
                "清空 Redis 会话",
                "删除历史消息",
                "清除聊天历史",
                "重开对话",
                "换个新会话",
                "重新初始化会话",
                "清空本轮历史")
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
