package org.example.service.intent;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IntentRecord {
    private final UserIntent intent;
    private final double confidence;
    private final String method;
    private final String userInput;
    private final long timestamp;

    public boolean isStable() {
        return confidence >= 0.6 && intent != null && intent != UserIntent.AMBIGUOUS;
    }
}
