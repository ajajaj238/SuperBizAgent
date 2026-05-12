package org.example.monitor;

import org.springframework.ai.chat.prompt.Prompt;

/**
 * 当底层 SDK 未返回 usage 时的简单兜底估算。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimatePromptTokens(Prompt prompt) {
        if (prompt == null) {
            return 0;
        }
        return estimateTextTokens(prompt.getContents());
    }

    public static int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int cjkCount = 0;
        int otherCount = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjkCount++;
            } else if (!Character.isWhitespace(c)) {
                otherCount++;
            }
        }

        int estimated = cjkCount + (int) Math.ceil(otherCount / 4.0);
        return Math.max(estimated, 1);
    }

    public static int estimateTokensByLength(int textLength) {
        if (textLength <= 0) {
            return 0;
        }
        return Math.max((int) Math.ceil(textLength / 3.5), 1);
    }
}
