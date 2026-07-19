package org.example.service.session;

import java.time.LocalDateTime;

public record SessionContext(
        Long userId,
        String username,
        String sessionId,
        LocalDateTime createTime,
        int messagePairCount,
        boolean hasSemanticMemory
) {
}
