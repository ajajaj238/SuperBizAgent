package org.example.service.intent;

import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class EmbeddingIntentClassifier implements IntentClassifier {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingIntentClassifier.class);
    static final double CONFIDENCE_THRESHOLD = 0.62;

    private final VectorSearchService vectorSearchService;

    public EmbeddingIntentClassifier(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @Override
    public IntentResult classify(String userInput, List<Map<String, String>> history) {
        try {
            List<VectorSearchService.IntentSearchResult> results =
                    vectorSearchService.searchIntentExamples(userInput, 1);
            if (results.isEmpty()) {
                return ambiguous(userInput, "intent_examples collection 无匹配结果");
            }

            VectorSearchService.IntentSearchResult best = results.get(0);
            UserIntent intent = parseIntent(best.getIntent());
            String method = best.getScore() >= CONFIDENCE_THRESHOLD ? "embedding" : "embedding_low_confidence";

            return IntentResult.builder()
                    .intent(intent)
                    .confidence(best.getScore())
                    .method(method)
                    .reason("最相似示例: " + best.getExample())
                    .rawInput(userInput)
                    .build();
        } catch (Exception e) {
            logger.warn("向量意图分类失败，降级为 AMBIGUOUS: {}", e.getMessage());
            return ambiguous(userInput, "向量分类失败: " + e.getMessage());
        }
    }

    private UserIntent parseIntent(String intent) {
        try {
            return UserIntent.valueOf(intent);
        } catch (Exception e) {
            return UserIntent.AMBIGUOUS;
        }
    }

    private IntentResult ambiguous(String userInput, String reason) {
        return IntentResult.builder()
                .intent(UserIntent.AMBIGUOUS)
                .confidence(0.0)
                .method("embedding_unavailable")
                .reason(reason)
                .rawInput(userInput)
                .build();
    }
}
