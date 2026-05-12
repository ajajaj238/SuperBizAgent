package org.example.monitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存当前线程对应的对话 Token 上下文，并支持通过 traceId 查询聚合对象。
 */
public final class TokenUsageContext {

    private static final ThreadLocal<String> CURRENT_TRACE_ID = new ThreadLocal<>();
    private static final Map<String, TokenUsageSummary> SUMMARIES = new ConcurrentHashMap<>();

    private TokenUsageContext() {
    }

    public static void bind(TokenUsageSummary summary) {
        if (summary == null) {
            return;
        }
        SUMMARIES.put(summary.getTraceId(), summary);
        CURRENT_TRACE_ID.set(summary.getTraceId());
    }

    public static String currentTraceId() {
        return CURRENT_TRACE_ID.get();
    }

    public static TokenUsageSummary currentSummary() {
        return get(currentTraceId());
    }

    public static TokenUsageSummary get(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        return SUMMARIES.get(traceId);
    }

    public static void clearCurrent() {
        CURRENT_TRACE_ID.remove();
    }

    public static void remove(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        SUMMARIES.remove(traceId);
    }
}
