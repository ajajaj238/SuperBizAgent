package org.example.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SessionIndex {
    private Long id;
    private Long userId;
    private String sessionId;
    private String title;
    private Integer status = 1;
    private Integer messageCount = 0;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
