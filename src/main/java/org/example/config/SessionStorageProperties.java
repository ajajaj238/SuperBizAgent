package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Getter
@Configuration
@ConfigurationProperties(prefix = "session.storage")
public class SessionStorageProperties {

    private Duration redisTtl = Duration.ofHours(24);
    private int redisMaxMessages = 20;
    private int compressionInterval = 0;
    private int compressionTokenThreshold = 2000;
    private int compressionMinMessages = 2;
    private int llmContextWindow = 10;
    private int semanticTopK = 3;
    private Duration semanticSearchTimeout = Duration.ofSeconds(3);
    private int dbPageSize = 20;
    private String personaPath = "./data/personas";

    public void setRedisTtl(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }

    public void setRedisMaxMessages(int redisMaxMessages) {
        this.redisMaxMessages = redisMaxMessages;
    }

    public void setCompressionInterval(int compressionInterval) {
        this.compressionInterval = compressionInterval;
    }

    public void setCompressionTokenThreshold(int compressionTokenThreshold) {
        this.compressionTokenThreshold = compressionTokenThreshold;
    }

    public void setCompressionMinMessages(int compressionMinMessages) {
        this.compressionMinMessages = compressionMinMessages;
    }

    public void setLlmContextWindow(int llmContextWindow) {
        this.llmContextWindow = llmContextWindow;
    }

    public void setSemanticTopK(int semanticTopK) {
        this.semanticTopK = semanticTopK;
    }

    public void setSemanticSearchTimeout(Duration semanticSearchTimeout) {
        this.semanticSearchTimeout = semanticSearchTimeout;
    }

    public void setDbPageSize(int dbPageSize) {
        this.dbPageSize = dbPageSize;
    }

    public void setPersonaPath(String personaPath) {
        this.personaPath = personaPath;
    }
}
