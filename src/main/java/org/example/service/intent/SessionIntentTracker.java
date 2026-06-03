package org.example.service.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionIntentTracker {

    private static final Logger logger = LoggerFactory.getLogger(SessionIntentTracker.class);
    private static final double STABLE_CONFIDENCE = 0.6;
    private static final double INHERIT_CONFIDENCE = 0.56;
    private static final int MAX_HISTORY_SIZE = 20;

    private final Map<String, List<IntentRecord>> intentHistories = new ConcurrentHashMap<>();

    public IntentResult resolveWithHistory(IntentResult current, String sessionId) {
        if (current == null || sessionId == null || sessionId.isBlank()) {
            return current;
        }
        if (current.getConfidence() >= STABLE_CONFIDENCE && !current.isAmbiguous()) {
            return current;
        }
        if (!shouldInherit(current)) {
            return current;
        }

        List<IntentRecord> history = intentHistories.getOrDefault(sessionId, Collections.emptyList());
        for (int i = history.size() - 1; i >= 0; i--) {
            IntentRecord prev = history.get(i);
            if (prev.isStable() && canInheritFrom(prev, current)) {
                logger.info("触发多轮上下文意图继承: sessionId={}, current={}, inherited={}",
                        sessionId, current.getIntent(), prev.getIntent());
                return IntentResult.builder()
                        .intent(prev.getIntent())
                        .confidence(INHERIT_CONFIDENCE)
                        .method("context_inherit")
                        .reason("当前低置信度，继承最近稳定意图: " + prev.getIntent())
                        .rawInput(current.getRawInput())
                        .build();
            }
        }

        return current;
    }

    private boolean shouldInherit(IntentResult current) {
        String rawInput = normalize(current.getRawInput());
        if (rawInput.isBlank()) {
            return false;
        }
        if (isProfileStatement(rawInput)) {
            return false;
        }
        if (isNewTopicQuestion(rawInput)) {
            return false;
        }
        return isShortFollowUp(rawInput);
    }

    private boolean canInheritFrom(IntentRecord previous, IntentResult current) {
        if (previous.getIntent() == UserIntent.TIME_QUERY
                || previous.getIntent() == UserIntent.SYSTEM_OPERATION
                || previous.getIntent() == UserIntent.ALERT_DIAGNOSIS) {
            return false;
        }
        return true;
    }

    private boolean isShortFollowUp(String input) {
        if (input.length() > 8) {
            return false;
        }
        if (input.contains("什么") || input.contains("为什么") || input.contains("怎么")) {
            return false;
        }
        return input.contains("呢")
                || input.contains("那")
                || input.contains("然后")
                || input.contains("还有")
                || input.contains("也")
                || input.endsWith("吗")
                || input.endsWith("么");
    }

    private boolean isProfileStatement(String input) {
        return input.startsWith("我是")
                || input.startsWith("我叫")
                || input.startsWith("我的名字是")
                || input.startsWith("我是个")
                || input.startsWith("本人是");
    }

    private boolean isNewTopicQuestion(String input) {
        return input.contains("你是谁")
                || input.contains("你叫什么")
                || input.contains("你叫啥")
                || input.contains("你的名字")
                || input.contains("你名字")
                || input.contains("介绍一下自己")
                || input.contains("介绍下自己")
                || input.contains("你能做什么")
                || input.contains("你可以做什么")
                || input.contains("你有什么能力")
                || input.contains("你会什么")
                || input.contains("是什么")
                || input.contains("为什么")
                || input.contains("怎么");
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    public void record(String sessionId, IntentResult result) {
        if (sessionId == null || sessionId.isBlank() || result == null) {
            return;
        }

        intentHistories.compute(sessionId, (id, oldHistory) -> {
            List<IntentRecord> newHistory = oldHistory == null ? new ArrayList<>() : new ArrayList<>(oldHistory);
            newHistory.add(IntentRecord.builder()
                    .intent(result.getIntent())
                    .confidence(result.getConfidence())
                    .method(result.getMethod())
                    .userInput(result.getRawInput())
                    .timestamp(System.currentTimeMillis())
                    .build());
            if (newHistory.size() > MAX_HISTORY_SIZE) {
                newHistory = new ArrayList<>(newHistory.subList(newHistory.size() - MAX_HISTORY_SIZE, newHistory.size()));
            }
            return newHistory;
        });
    }

    public List<IntentRecord> getHistory(String sessionId) {
        return new ArrayList<>(intentHistories.getOrDefault(sessionId, Collections.emptyList()));
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            intentHistories.remove(sessionId);
        }
    }
}
