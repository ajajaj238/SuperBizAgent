package org.example.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 统一负责 token 使用量的埋点、日志与指标上报。
 */
@Component
public class TokenUsageRecorder {

    private static final Logger tokenLogger = LoggerFactory.getLogger("ai.token.usage");
    private static final Logger exchangeLogger = LoggerFactory.getLogger("ai.llm.exchange");
    private static final String DEFAULT_CHAT_MODEL = "qwen-plus";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v4";

    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public TokenUsageRecorder(MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    public TokenUsageSummary beginConversation(String sessionId, String endpoint, String question) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        TokenUsageSummary summary = new TokenUsageSummary(traceId, sessionId, endpoint, question == null ? 0 : question.length());
        TokenUsageContext.bind(summary);
        MDC.put("tokenTraceId", traceId);
        return summary;
    }

    public void completeConversationSuccess(int answerLength) {
        TokenUsageSummary summary = TokenUsageContext.currentSummary();
        if (summary == null) {
            return;
        }
        summary.markSuccess(answerLength);
        emitSummary(summary);
        cleanup(summary.getTraceId());
    }

    public void completeConversationError(String errorMessage) {
        TokenUsageSummary summary = TokenUsageContext.currentSummary();
        if (summary == null) {
            return;
        }
        summary.markFailed(errorMessage);
        emitSummary(summary);
        cleanup(summary.getTraceId());
    }

    public void recordChatUsage(String traceId, Prompt prompt, ChatResponse response, String configuredModelName) {
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        String model = metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()
                ? metadata.getModel()
                : (configuredModelName == null || configuredModelName.isBlank() ? DEFAULT_CHAT_MODEL : configuredModelName);
        String promptText = extractPromptText(prompt);
        String answerText = extractAnswerText(response);

        if (usage != null) {
            recordChatUsage(traceId, model,
                    safeValue(usage.getPromptTokens()),
                    safeValue(usage.getCompletionTokens()),
                    safeValue(usage.getTotalTokens()),
                    "actual");
            recordChatExchange(traceId, model, promptText, answerText, "non_stream");
            return;
        }

        //降级手动估算token
        int estimatedPromptTokens = TokenEstimator.estimatePromptTokens(prompt);
        int estimatedCompletionTokens = TokenEstimator.estimateTextTokens(answerText);
        recordChatUsage(traceId, model, estimatedPromptTokens, estimatedCompletionTokens,
                estimatedPromptTokens + estimatedCompletionTokens, "estimated");
        recordChatExchange(traceId, model, promptText, answerText, "non_stream");
    }

    public void recordChatUsage(String traceId, String model, int promptTokens, int completionTokens, int totalTokens, String usageSource) {
        TokenUsageSummary summary = TokenUsageContext.get(traceId);
        if (summary != null) {
            summary.addLlmUsage(promptTokens, completionTokens, totalTokens);
        }

        incrementCounter("ai_llm_prompt_tokens_total", promptTokens, "model", model, "usage_source", usageSource);
        incrementCounter("ai_llm_completion_tokens_total", completionTokens, "model", model, "usage_source", usageSource);
        incrementCounter("ai_llm_total_tokens_total", totalTokens, "model", model, "usage_source", usageSource);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("event", "ai_token_usage_detail");
        detail.put("traceId", traceId);
        detail.put("phase", "llm");
        detail.put("model", model);
        detail.put("promptTokens", promptTokens);
        detail.put("completionTokens", completionTokens);
        detail.put("totalTokens", totalTokens);
        detail.put("usageSource", usageSource);
        tokenLogger.info(toJson(detail));
    }

    public void recordEmbeddingUsage(String model, Integer totalTokens, int textCount, int totalTextLength) {
        int finalTokens;
        String usageSource;
        if (totalTokens != null && totalTokens > 0) {
            finalTokens = totalTokens;
            usageSource = "actual";
        } else {
            finalTokens = TokenEstimator.estimateTokensByLength(totalTextLength);
            usageSource = "estimated";
        }

        String traceId = TokenUsageContext.currentTraceId();
        TokenUsageSummary summary = TokenUsageContext.currentSummary();
        if (summary != null) {
            summary.addEmbeddingUsage(finalTokens);
        }

        String finalModel = (model == null || model.isBlank()) ? DEFAULT_EMBEDDING_MODEL : model;
        incrementCounter("ai_embedding_tokens_total", finalTokens, "model", finalModel, "usage_source", usageSource);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("event", "ai_token_usage_detail");
        detail.put("traceId", traceId);
        detail.put("phase", "embedding");
        detail.put("model", finalModel);
        detail.put("textCount", textCount);
        detail.put("totalTokens", finalTokens);
        detail.put("usageSource", usageSource);
        tokenLogger.info(toJson(detail));
    }

    public void recordIntent(String sessionId, String intent, double confidence, String method, String reason) {
        Counter.builder("ai_intent_classification_total")
                .tag("intent", sanitizeTag(intent))
                .tag("method", sanitizeTag(method))
                .register(meterRegistry)
                .increment();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("event", "ai_intent_classification");
        detail.put("traceId", TokenUsageContext.currentTraceId());
        detail.put("sessionId", sessionId);
        detail.put("intent", intent);
        detail.put("confidence", confidence);
        detail.put("method", method);
        detail.put("reason", reason);
        tokenLogger.info(toJson(detail));
    }

    public void recordChatExchange(String traceId, String model, String promptText, String responseText, String mode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("event", "ai_llm_exchange");
        detail.put("traceId", traceId);
        detail.put("phase", "llm");
        detail.put("model", (model == null || model.isBlank()) ? DEFAULT_CHAT_MODEL : model);
        detail.put("mode", mode);
        detail.put("prompt", promptText == null ? "" : promptText);
        detail.put("response", responseText == null ? "" : responseText);
        exchangeLogger.info(toJson(detail));
    }

    private void emitSummary(TokenUsageSummary summary) {
        incrementCounter("ai_conversation_count", 1, "endpoint", summary.getEndpoint(), "status", summary.isSuccess() ? "success" : "error");
        Timer.builder("ai_conversation_duration_ms")
                .tag("endpoint", summary.getEndpoint())
                .tag("status", summary.isSuccess() ? "success" : "error")
                .register(meterRegistry)
                .record(summary.getDurationMillis(), TimeUnit.MILLISECONDS);

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("event", "ai_token_usage_summary");
        log.put("traceId", summary.getTraceId());
        log.put("sessionId", summary.getSessionId());
        log.put("endpoint", summary.getEndpoint());
        log.put("chatModel", DEFAULT_CHAT_MODEL);
        log.put("embeddingModel", DEFAULT_EMBEDDING_MODEL);
        log.put("questionLength", summary.getQuestionLength());
        log.put("answerLength", summary.getAnswerLength());
        log.put("llmCallCount", summary.getLlmCallCount());
        log.put("embeddingCallCount", summary.getEmbeddingCallCount());
        log.put("promptTokens", summary.getLlmPromptTokens());
        log.put("completionTokens", summary.getLlmCompletionTokens());
        log.put("llmTotalTokens", summary.getLlmTotalTokens());
        log.put("embeddingTokens", summary.getEmbeddingTokens());
        log.put("grandTotalTokens", summary.getGrandTotalTokens());
        log.put("durationMs", summary.getDurationMillis());
        log.put("success", summary.isSuccess());
        if (!summary.isSuccess()) {
            log.put("errorMessage", summary.getErrorMessage());
        }
        tokenLogger.info(toJson(log));
    }

    private void cleanup(String traceId) {
        TokenUsageContext.clearCurrent();
        TokenUsageContext.remove(traceId);
        MDC.remove("tokenTraceId");
    }

    private void incrementCounter(String metricName, double value, String tagKeyOne, String tagValueOne, String tagKeyTwo, String tagValueTwo) {
        if (value <= 0) {
            return;
        }
        Counter.builder(metricName)
                .tag(tagKeyOne, sanitizeTag(tagValueOne))
                .tag(tagKeyTwo, sanitizeTag(tagValueTwo))
                .register(meterRegistry)
                .increment(value);
    }

    private String sanitizeTag(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }

    private String extractAnswerText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private String extractPromptText(Prompt prompt) {
        if (prompt == null) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message == null) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            lines.add(message.getMessageType() + ": " + text);
        }

        if (!lines.isEmpty()) {
            return String.join("\n", lines);
        }
        return prompt.getContents();
    }

    private int safeValue(Integer value) {
        return value == null ? 0 : value;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return payload.toString();
        }
    }
}
