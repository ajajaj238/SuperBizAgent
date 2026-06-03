package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Getter
@Configuration
@ConfigurationProperties(prefix = "session.storage")
public class SessionStorageProperties {

    private String path = "./data/sessions";
    private Duration redisTtl = Duration.ofHours(24);
    private int redisMaxMessages = 50;
    private int compressionInterval = 5;
    private int llmContextWindow = 10;
    private int semanticTopK = 3;

    public void setPath(String path) {
        this.path = path;
    }

    public void setRedisTtl(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }

    public void setRedisMaxMessages(int redisMaxMessages) {
        this.redisMaxMessages = redisMaxMessages;
    }

    public void setCompressionInterval(int compressionInterval) {
        this.compressionInterval = compressionInterval;
    }

    public void setLlmContextWindow(int llmContextWindow) {
        this.llmContextWindow = llmContextWindow;
    }

    public void setSemanticTopK(int semanticTopK) {
        this.semanticTopK = semanticTopK;
    }
}
