package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 面向 RAG 检索的 Query Rewrite。
 * 优先使用 LLM 生成独立检索 query，失败时回退到规则改写。
 */
@Service
public class QueryRewriteService {

    private static final Logger rewriteLogger = LoggerFactory.getLogger("ai.query.rewrite");
    private static final int MAX_CONTEXT_CHARS = 80;
    private static final int MAX_REWRITTEN_CHARS = 160;
    private static final Pattern FOLLOW_UP_PATTERN = Pattern.compile(
            ".*(那|那么|这个|这个呢|它|它呢|还有|也|呢|怎么处理|怎么排查|如何处理|如何排查|日志|指标|告警).*");
    private static final String SYSTEM_PROMPT = """
            你是 RAG 检索 query 改写器。你的任务是把多轮对话中的当前问题改写成一个独立、完整、适合向量检索的中文查询。

            要求：
            - 只补全检索必要上下文，不回答问题。
            - 保留服务名、指标名、告警名、错误码、时间范围等关键信息。
            - 当前问题已经完整时，needRewrite=false。
            - 不要编造历史中没有出现的事实。
            - 输出严格 JSON，不要输出 Markdown。

            JSON 格式：
            {"needRewrite":true,"standaloneQuery":"...","reason":"..."}
            """;

    private final ObjectMapper objectMapper;
    private final TokenUsageRecorder tokenUsageRecorder;

    public QueryRewriteService(ObjectMapper objectMapper, TokenUsageRecorder tokenUsageRecorder) {
        this.objectMapper = objectMapper;
        this.tokenUsageRecorder = tokenUsageRecorder;
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

        String previousUserQuestion = lastUserQuestion(history);
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
            ChatModel model = new MonitoringChatModel(createRewriteModel(), tokenUsageRecorder,
                    DashScopeChatModel.DEFAULT_MODEL_NAME);
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

    private DashScopeChatModel createRewriteModel() {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.1)
                        .withMaxToken(positiveOrDefault(llmMaxToken, 256))
                        .withTopP(0.5)
                        .build())
                .build();
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
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            Map<String, String> item = history.get(i);
            if (item == null) {
                continue;
            }
            String role = "assistant".equals(item.get("role")) ? "助手" : "用户";
            String content = truncate(normalize(item.get("content")), positiveOrDefault(maxContextChars, MAX_CONTEXT_CHARS));
            if (!content.isBlank()) {
                lines.add(role + ": " + content);
            }
        }
        return lines.isEmpty() ? "无" : String.join("\n", lines);
    }

    private String parseStandaloneQuery(String responseText) throws Exception {
        String json = extractJson(responseText);
        JsonNode node = objectMapper.readTree(json);
        boolean needRewrite = node.path("needRewrite").asBoolean(false);
        if (!needRewrite) {
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
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private boolean shouldRewrite(String question) {
        String normalized = normalize(question);
        if (normalized.length() <= 16 && FOLLOW_UP_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        if (normalized.length() <= 8) {
            return true;
        }
        return startsWithFollowUpCue(normalized);
    }

    private boolean startsWithFollowUpCue(String question) {
        return question.startsWith("那")
                || question.startsWith("那么")
                || question.startsWith("这个")
                || question.startsWith("它")
                || question.startsWith("还有")
                || question.startsWith("也");
    }

    private String buildStandaloneQuery(String previousUserQuestion, String question) {
        String context = truncate(normalize(previousUserQuestion), positiveOrDefault(maxContextChars, MAX_CONTEXT_CHARS));
        String current = normalizeFollowUpPrefix(question);
        String rewritten = "基于上一轮问题：" + context + "；当前追问：" + current;
        return truncate(rewritten, positiveOrDefault(maxRewrittenChars, MAX_REWRITTEN_CHARS));
    }

    private String normalizeFollowUpPrefix(String question) {
        String normalized = normalize(question);
        normalized = normalized.replaceFirst("^(那|那么|这个|它|还有|也)[，,、\\s]*", "");
        return normalized.isBlank() ? question : normalized;
    }

    private String lastUserQuestion(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> item = history.get(i);
            if (item == null || !"user".equals(item.get("role"))) {
                continue;
            }
            String content = normalize(item.get("content"));
            if (!content.isBlank()) {
                return content;
            }
        }
        return "";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
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
