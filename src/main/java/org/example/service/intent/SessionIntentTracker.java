package org.example.service.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

        List<IntentRecord> history = intentHistories.getOrDefault(sessionId, Collections.emptyList());
        for (int i = history.size() - 1; i >= 0; i--) {
            IntentRecord prev = history.get(i);
            if (prev.isStable()) {
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
