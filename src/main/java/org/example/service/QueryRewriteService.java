package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.monitor.MonitoringChatModel;
import org.example.monitor.TokenUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将多轮对话中的短追问改写为独立查询。
 * LLM 负责语义补全，失败时使用规则结果兜底。
 */
@Service
public class QueryRewriteService {

    private static final Logger rewriteLogger = LoggerFactory.getLogger("ai.query.rewrite");
    private static final int MAX_CONTEXT_CHARS = 80;
    private static final int MAX_REWRITTEN_CHARS = 160;
    /**
     * 长度 8-16 的追问信号：仅对话性 cue（"怎么办"这类完整诉求不算跟进信号）
     */
    private static final Pattern FOLLOW_UP_CUE_PATTERN = Pattern.compile(
            ".*(呢|那|那么|这个|它呢|还有|也一样|继续|接着|和.*比|相比|对比).*");
    private static final Pattern CONTEXT_ANCHOR_PATTERN = Pattern.compile(
            ".*(怎么|如何|处理|排查|原因|解决|查询|告警|飙高|过高|异常|报错).*");
    private static final Pattern COMPLETE_INTENT_SUFFIX = Pattern.compile(
            "(怎么办|怎么处理|怎么排查|怎么解决|怎么定位|怎么优化|如何处理|如何排查|如何解决|如何优化|是什么|有哪些|什么原因|什么情况)$");
    private static final List<TopicGroup> TOPIC_GROUPS = List.of(
            new TopicGroup("CPU", List.of("cpu", "处理器")),
            new TopicGroup("内存", List.of("内存", "memory", "ram")),
            new TopicGroup("磁盘", List.of("磁盘", "硬盘", "disk")),
            new TopicGroup("网络", List.of("网络", "带宽", "network")),
            new TopicGroup("服务", List.of("服务", "service")),
            new TopicGroup("网关", List.of("网关", "gateway")),
            new TopicGroup("数据库", List.of("数据库", "database")),
            new TopicGroup("Redis", List.of("redis")),
            new TopicGroup("MySQL", List.of("mysql")),
            new TopicGroup("JVM", List.of("jvm")),
            new TopicGroup("Pod", List.of("pod")),
            new TopicGroup("容器", List.of("容器", "container")),
            new TopicGroup("线程", List.of("线程", "thread")),
            new TopicGroup("进程", List.of("进程", "process")),
            new TopicGroup("接口", List.of("接口", "api")),
            new TopicGroup("日志", List.of("日志", "log")),
            new TopicGroup("告警", List.of("告警", "alert"))
    );
    private static final String SYSTEM_PROMPT = """
            你是查询改写器。请把多轮对话中的当前追问改写成一个独立、完整的问题，
            以便后续进行意图识别和知识检索。

            要求：
            - 只补全当前问题缺失的主语、对象和诉求，不回答问题。
            - 结合最近对话判断用户真正承接的主题，不要机械使用紧邻但无关的闲聊。
            - 当前问题明确出现新对象时，新对象必须替换上一轮对象，只继承上一轮的诉求。
              例如上一轮是“CPU飙高怎么办”，当前是“内存呢”，应改写为“内存使用率飙高怎么办”，
              不能改写为“CPU飙高时内存如何处理”。
            - 保留服务名、指标名、告警名、错误码、时间范围等关键信息。
            - 当前问题已经完整时，needRewrite=false。
            - 不要编造历史对话中没有出现的事实。
            - 输出严格 JSON，不要输出 Markdown。

            JSON 格式：
            {"needRewrite":true,"standaloneQuery":"...","reason":"..."}
            """;

    private final ObjectMapper objectMapper;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final ModelRoutingService modelRoutingService;

    public QueryRewriteService(ObjectMapper objectMapper,
                               TokenUsageRecorder tokenUsageRecorder,
                               ModelRoutingService modelRoutingService) {
        this.objectMapper = objectMapper;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.modelRoutingService = modelRoutingService;
    }

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean enabled;

    @Value("${rag.query-rewrite.llm-enabled:true}")
    private boolean llmEnabled;

    @Value("${rag.query-rewrite.llm-max-token:256}")
    private int llmMaxToken;

    @Value("${rag.query-rewrite.max-context-chars:80}")
    private int maxContextChars;

    @Value("${rag.query-rewrite.max-rewritten-chars:160}")
    private int maxRewrittenChars;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    public RewriteResult rewriteForRetrieval(String question, List<Map<String, String>> history) {
        String safeQuestion = normalize(question);
        if (!enabled) {
            return RewriteResult.notRewritten(safeQuestion, "disabled");
        }
        if (safeQuestion.isBlank()) {
            return RewriteResult.notRewritten(question, "blank_question");
        }
        if (isSelfContainedShortQuestion(safeQuestion)) {
            return RewriteResult.notRewritten(safeQuestion, "self_contained_question");
        }

        String previousUserQuestion = lastContextQuestion(history);
        if (previousUserQuestion.isBlank()) {
            return RewriteResult.notRewritten(safeQuestion, "no_previous_user_question");
        }
        if (!shouldRewrite(safeQuestion)) {
            return RewriteResult.notRewritten(safeQuestion, "no_followup_signal");
        }

        String ruleRewritten = buildStandaloneQuery(previousUserQuestion, safeQuestion);
        RewriteCandidate candidate = llmEnabled
                ? rewriteWithLlmOrFallback(safeQuestion, history, ruleRewritten)
                : new RewriteCandidate(ruleRewritten, "rule", "llm_disabled");
        String rewritten = candidate.query();
        if (rewritten.equals(safeQuestion) || rewritten.isBlank()) {
            return RewriteResult.notRewritten(safeQuestion, candidate.reason(), previousUserQuestion);
        }

        return new RewriteResult(safeQuestion, rewritten, true,
                candidate.method(), candidate.reason(), previousUserQuestion);
    }

    private RewriteCandidate rewriteWithLlmOrFallback(String question,
                                                      List<Map<String, String>> history,
                                                      String fallbackQuery) {
        if (dashScopeApiKey == null || dashScopeApiKey.isBlank()) {
            return new RewriteCandidate(fallbackQuery, "rule", "dashscope_api_key_missing");
        }
        try {
            ModelRoutingService.ModelSpec rewriteSpec =
                    modelRoutingService.forTask(ModelRoutingService.ModelTask.QUERY_REWRITE);
            ChatModel model = new MonitoringChatModel(
                    createRewriteModel(rewriteSpec), tokenUsageRecorder, rewriteSpec.modelName());
            ChatResponse response = model.call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(buildRewritePrompt(question, history))
            )));
            String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? ""
                    : response.getResult().getOutput().getText();
            rewriteLogger.info("event=query_rewrite_llm_raw original='{}' response='{}'", question, text);
            String rewritten = parseStandaloneQuery(text);
            if (!rewritten.isBlank()) {
                String previousQuestion = lastContextQuestion(history);
                if (!isTopicConsistent(question, previousQuestion, rewritten)) {
                    return new RewriteCandidate(fallbackQuery, "rule", "llm_topic_mismatch");
                }
                return new RewriteCandidate(
                        truncate(normalize(rewritten), positiveOrDefault(maxRewrittenChars, MAX_REWRITTEN_CHARS)),
                        "llm",
                        "llm_rewrite");
            }
            return new RewriteCandidate(fallbackQuery, "rule", "llm_no_rewrite_or_blank");
        } catch (Exception e) {
            return new RewriteCandidate(fallbackQuery, "rule", "llm_failed:" + e.getClass().getSimpleName());
        }
    }

    private DashScopeChatModel createRewriteModel(ModelRoutingService.ModelSpec spec) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
        ModelRoutingService.ModelSpec limitedSpec = new ModelRoutingService.ModelSpec(
                spec.tier(),
                spec.modelName(),
                0.1,
                positiveOrDefault(llmMaxToken, 256),
                0.5);
        return modelRoutingService.createChatModel(dashScopeApi, limitedSpec);
    }

    private String buildRewritePrompt(String question, List<Map<String, String>> history) {
        return """
                最近对话：
                %s

                当前问题：
                %s
                """.formatted(buildHistoryText(history), question);
    }

    private String buildHistoryText(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "无";
        }
        List<String> lines = new ArrayList<>();
        int start = Math.max(0, history.size() - 8);
        for (int i = start; i < history.size(); i++) {
            Map<String, String> item = history.get(i);
            if (item == null) {
                continue;
            }
            String role = "assistant".equals(item.get("role")) ? "助手" : "用户";
            String content = truncate(
                    normalize(item.get("content")),
                    positiveOrDefault(maxContextChars, MAX_CONTEXT_CHARS));
            if (!content.isBlank()) {
                lines.add(role + ": " + content);
            }
        }
        return lines.isEmpty() ? "无" : String.join("\n", lines);
    }

    private String parseStandaloneQuery(String responseText) throws Exception {
        JsonNode node = objectMapper.readTree(extractJson(responseText));
        if (!node.path("needRewrite").asBoolean(false)) {
            return "";
        }
        return normalize(node.path("standaloneQuery").asText(""));
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private boolean shouldRewrite(String question) {
        String normalized = normalize(question);
        if (isLikelyCompleteShortQuestion(normalized)) {
            return false;
        }
        if (normalized.length() <= 8) {
            return true;
        }
        if (startsWithFollowUpCue(normalized)) {
            return true;
        }
        return normalized.length() <= 16 && FOLLOW_UP_CUE_PATTERN.matcher(normalized).matches();
    }

    private boolean startsWithFollowUpCue(String question) {
        return question.startsWith("那")
                || question.startsWith("那么")
                || question.startsWith("这个")
                || question.startsWith("它")
                || question.startsWith("还有")
                || question.startsWith("继续")
                || question.startsWith("接着");
    }

    private boolean isSelfContainedShortQuestion(String question) {
        String normalized = normalize(question).toLowerCase();
        return normalized.matches(".*(你是谁|你叫什么|我是谁|我叫什么|介绍一下自己|你能做什么).*")
                || normalized.matches(".*(现在几点|当前时间|今天几号|今天日期|星期几|周几|时间).*")
                || normalized.matches(".*(清空历史|删除会话|开始新对话|重置会话).*")
                || normalized.matches("^(你好|您好|hello|hi|嗨|谢谢|感谢|再见|拜拜|好的|知道了|明白|嗯|好)[!！。，,\\s、]*$")
                || normalized.matches("^(好的|嗯|嗯嗯|知道了|明白了)[，,、\\s]*(谢谢|感谢|好的)?[!！。，,\\s]*$");
    }

    /**
     * 判断是否为"已完整"的短问题（含主题词 + 完整诉求结尾），避免被误改写。
     * 例如"磁盘满了怎么办"完整不改写；"内存呢""它怎么解决"仍需改写。
     */
    private boolean isLikelyCompleteShortQuestion(String question) {
        String normalized = normalize(question).toLowerCase(Locale.ROOT);
        if (normalized.length() > 12) {
            return false;
        }
        if (!COMPLETE_INTENT_SUFFIX.matcher(normalized).find()) {
            return false;
        }
        return !topicsIn(normalized).isEmpty() || containsAlnumWord(normalized);
    }

    private boolean containsAlnumWord(String text) {
        return Pattern.compile("[a-z0-9]{2,}").matcher(text).find();
    }

    private String buildStandaloneQuery(String previousUserQuestion, String question) {
        String topicReplacement = buildTopicReplacement(previousUserQuestion, question);
        if (!topicReplacement.isBlank()) {
            return truncate(
                    topicReplacement,
                    positiveOrDefault(maxRewrittenChars, MAX_REWRITTEN_CHARS));
        }
        String context = truncate(
                normalize(previousUserQuestion),
                positiveOrDefault(maxContextChars, MAX_CONTEXT_CHARS));
        String current = normalizeFollowUpPrefix(question);
        return truncate(
                "基于上一轮问题：" + context + "；当前追问：" + current,
                positiveOrDefault(maxRewrittenChars, MAX_REWRITTEN_CHARS));
    }

    private String buildTopicReplacement(String previousQuestion, String currentQuestion) {
        Set<TopicGroup> currentTopics = topicsIn(currentQuestion);
        Set<TopicGroup> previousTopics = topicsIn(previousQuestion);
        if (currentTopics.size() != 1 || previousTopics.isEmpty()) {
            return "";
        }

        TopicGroup currentTopic = currentTopics.iterator().next();
        TopicGroup previousTopic = previousTopics.stream()
                .filter(topic -> !topic.equals(currentTopic))
                .findFirst()
                .orElse(null);
        if (previousTopic == null) {
            return "";
        }

        String rewritten = previousQuestion;
        for (String alias : previousTopic.aliases()) {
            rewritten = replaceIgnoreCase(rewritten, alias, currentTopic.canonical());
        }
        return rewritten.equals(previousQuestion) ? "" : normalize(rewritten);
    }

    private boolean isTopicConsistent(String currentQuestion,
                                      String previousQuestion,
                                      String rewrittenQuestion) {
        Set<TopicGroup> currentTopics = topicsIn(currentQuestion);
        if (currentTopics.isEmpty()) {
            return true;
        }

        Set<TopicGroup> rewrittenTopics = topicsIn(rewrittenQuestion);
        if (!rewrittenTopics.containsAll(currentTopics)) {
            return false;
        }
        if (isComparisonQuestion(currentQuestion)) {
            return true;
        }

        Set<TopicGroup> staleTopics = topicsIn(previousQuestion);
        staleTopics.removeAll(currentTopics);
        for (TopicGroup staleTopic : staleTopics) {
            if (rewrittenTopics.contains(staleTopic)) {
                return false;
            }
        }
        return true;
    }

    private boolean isComparisonQuestion(String question) {
        String normalized = normalize(question);
        return normalized.contains("和")
                || normalized.contains("与")
                || normalized.contains("相比")
                || normalized.contains("对比");
    }

    private Set<TopicGroup> topicsIn(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        Set<TopicGroup> topics = new LinkedHashSet<>();
        for (TopicGroup topic : TOPIC_GROUPS) {
            if (topic.aliases().stream()
                    .map(alias -> alias.toLowerCase(Locale.ROOT))
                    .anyMatch(normalized::contains)) {
                topics.add(topic);
            }
        }
        return topics;
    }

    private String replaceIgnoreCase(String source, String target, String replacement) {
        return source.replaceAll(
                "(?i)" + Pattern.quote(target),
                Matcher.quoteReplacement(replacement));
    }

    private String normalizeFollowUpPrefix(String question) {
        String normalized = normalize(question)
                .replaceFirst("^(那|那么|这个|它|还有|继续|接着)[，,。\\s]*", "");
        return normalized.isBlank() ? question : normalized;
    }

    private String lastContextQuestion(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        String fallback = "";
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> item = history.get(i);
            if (item == null || !"user".equals(item.get("role"))) {
                continue;
            }
            String content = normalize(item.get("content"));
            if (content.isBlank() || isSelfContainedShortQuestion(content)) {
                continue;
            }
            if (fallback.isBlank()) {
                fallback = content;
            }
            if (content.length() > 8 || CONTEXT_ANCHOR_PATTERN.matcher(content).matches()) {
                return content;
            }
        }
        return fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars);
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private record RewriteCandidate(String query, String method, String reason) {
    }

    private record TopicGroup(String canonical, List<String> aliases) {
    }

    public record RewriteResult(String originalQuery,
                                String rewrittenQuery,
                                boolean rewritten,
                                String method,
                                String reason,
                                String previousUserQuestion) {
        public static RewriteResult notRewritten(String query) {
            return notRewritten(query, "not_rewritten");
        }

        public static RewriteResult notRewritten(String query, String reason) {
            return notRewritten(query, reason, "");
        }

        public static RewriteResult notRewritten(String query, String reason, String previousUserQuestion) {
            return new RewriteResult(query, query, false, "none", reason, previousUserQuestion);
        }
    }
}
