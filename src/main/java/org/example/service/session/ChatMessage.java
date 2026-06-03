package org.example.service.session;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    private String msgId;
    private String role;
    private String content;
    private LocalDateTime timestamp;

    public static ChatMessage user(String content) {
        return of("user", content);
    }

    public static ChatMessage assistant(String content) {
        return of("assistant", content);
    }

    public static ChatMessage of(String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setMsgId("m_" + UUID.randomUUID().toString().replace("-", ""));
        msg.setRole(role);
        msg.setContent(content);
        msg.setTimestamp(LocalDateTime.now());
        return msg;
    }
}
