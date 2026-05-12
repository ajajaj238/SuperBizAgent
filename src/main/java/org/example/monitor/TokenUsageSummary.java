package org.example.monitor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次对话请求的 Token 统计聚合对象。
 */
public class TokenUsageSummary {

    private final String traceId;
    private final String sessionId;
    private final String endpoint;
    private final int questionLength;
    private final long startTimeMillis;
    private final AtomicLong llmPromptTokens = new AtomicLong();
    private final AtomicLong llmCompletionTokens = new AtomicLong();
    private final AtomicLong llmTotalTokens = new AtomicLong();
    private final AtomicLong embeddingTokens = new AtomicLong();
    private final AtomicInteger llmCallCount = new AtomicInteger();
    private final AtomicInteger embeddingCallCount = new AtomicInteger();
    private volatile int answerLength;
    private volatile boolean success;
    private volatile String errorMessage;

    public TokenUsageSummary(String traceId, String sessionId, String endpoint, int questionLength) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.endpoint = endpoint;
        this.questionLength = questionLength;
        this.startTimeMillis = System.currentTimeMillis();
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public int getQuestionLength() {
        return questionLength;
    }

    public long getLlmPromptTokens() {
        return llmPromptTokens.get();
    }

    public long getLlmCompletionTokens() {
        return llmCompletionTokens.get();
    }

    public long getLlmTotalTokens() {
        return llmTotalTokens.get();
    }

    public long getEmbeddingTokens() {
        return embeddingTokens.get();
    }

    public int getLlmCallCount() {
        return llmCallCount.get();
    }

    public int getEmbeddingCallCount() {
        return embeddingCallCount.get();
    }

    public int getAnswerLength() {
        return answerLength;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getDurationMillis() {
        return System.currentTimeMillis() - startTimeMillis;
    }

    public long getGrandTotalTokens() {
        return getLlmTotalTokens() + getEmbeddingTokens();
    }

    public void addLlmUsage(int promptTokens, int completionTokens, int totalTokens) {
        llmPromptTokens.addAndGet(Math.max(promptTokens, 0));
        llmCompletionTokens.addAndGet(Math.max(completionTokens, 0));
        llmTotalTokens.addAndGet(Math.max(totalTokens, 0));
        llmCallCount.incrementAndGet();
    }

    public void addEmbeddingUsage(int totalTokens) {
        embeddingTokens.addAndGet(Math.max(totalTokens, 0));
        embeddingCallCount.incrementAndGet();
    }

    public void markSuccess(int answerLength) {
        this.success = true;
        this.answerLength = Math.max(answerLength, 0);
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.success = false;
        this.answerLength = 0;
        this.errorMessage = errorMessage;
    }
}
