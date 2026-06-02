package org.example.service.intent;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IntentResult {
    private final UserIntent intent;
    private final double confidence;
    private final String method;
    private final String reason;
    private final String rawInput;

    public boolean isAmbiguous() {
        return intent == null || intent == UserIntent.AMBIGUOUS;
    }

    public IntentResult withMethod(String newMethod, double newConfidence, String newReason) {
        return IntentResult.builder()
                .intent(intent)
                .confidence(newConfidence)
                .method(newMethod)
                .reason(newReason)
                .rawInput(rawInput)
                .build();
    }
}
