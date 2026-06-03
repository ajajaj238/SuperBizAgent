package org.example.service.intent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class HybridIntentClassifier {

    private final EmbeddingIntentClassifier embeddingClassifier;
    private final LlmZeroShotClassifier llmClassifier;
    private final SessionIntentTracker intentTracker;

    public HybridIntentClassifier(EmbeddingIntentClassifier embeddingClassifier,
                                  LlmZeroShotClassifier llmClassifier,
                                  SessionIntentTracker intentTracker) {
        this.embeddingClassifier = embeddingClassifier;
        this.llmClassifier = llmClassifier;
        this.intentTracker = intentTracker;
    }

    public IntentResult classify(String userInput, String sessionId, List<Map<String, String>> history) {
        IntentResult embeddingResult = embeddingClassifier.classify(userInput, history);
        if (embeddingResult.getConfidence() >= EmbeddingIntentClassifier.CONFIDENCE_THRESHOLD
                && !embeddingResult.isAmbiguous()) {
            return embeddingResult;
        }

        IntentResult quickMatch = quickRegexMatch(userInput);
        if (quickMatch != null) {
            return quickMatch;
        }

        IntentResult contextResult = intentTracker.resolveWithHistory(embeddingResult, sessionId);
        if (contextResult != null && contextResult.getConfidence() >= 0.55 && !contextResult.isAmbiguous()) {
            return contextResult;
        }

        return llmClassifier.classify(userInput, history);
    }

    private IntentResult quickRegexMatch(String userInput) {
        if (userInput == null) {
            return null;
        }
        String text = userInput.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }

        if (text.matches("^(你好|您好|hello|hi|嗨|谢谢|感谢|再见|拜拜)[!！。\\s]*$")) {
            return result(UserIntent.CHITCHAT, 0.95, "quick_regex", "问候/致谢/告别", userInput);
        }
        if (isAssistantIdentityQuestion(text)) {
            return result(UserIntent.CHITCHAT, 0.94, "quick_regex", "询问助手身份/能力", userInput);
        }
        if (text.matches("^(我是|我叫|我的名字是|本人是).+")) {
            return result(UserIntent.CHITCHAT, 0.92, "quick_regex", "用户自我介绍/身份声明", userInput);
        }
        if (text.matches(".*(现在几点|当前时间|今天几号|今天日期|星期几|周几).*")) {
            return result(UserIntent.TIME_QUERY, 0.95, "quick_regex", "时间查询", userInput);
        }
        if (text.matches(".*(清空历史|删除会话|开始新对话|重置会话|重置).*")) {
            return result(UserIntent.SYSTEM_OPERATION, 0.9, "quick_regex", "系统操作", userInput);
        }

        return null;
    }

    private boolean isAssistantIdentityQuestion(String text) {
        return text.matches(".*(你是谁|你叫什么|你叫啥|你的名字|你名字|介绍一下自己|介绍下自己|你能做什么|你可以做什么|你有什么能力|你会什么).*");
    }

    private IntentResult result(UserIntent intent, double confidence, String method, String reason, String rawInput) {
        return IntentResult.builder()
                .intent(intent)
                .confidence(confidence)
                .method(method)
                .reason(reason)
                .rawInput(rawInput)
                .build();
    }
}
