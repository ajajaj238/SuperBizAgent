package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Configuration
@ConfigurationProperties(prefix = "rag.sync")
public class RagSyncProperties {

    private boolean enabled = true;
    private long intervalMs = 60000;
    private long initialDelayMs = 30000;
    private String indexFile = "./data/rag-document-index.json";
    private List<String> sourcePaths = new ArrayList<>(List.of("./aiops-docs", "./uploads"));
    private List<String> allowedExtensions = new ArrayList<>(List.of("md", "markdown", "txt"));

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public void setIndexFile(String indexFile) {
        this.indexFile = indexFile;
    }

    public void setSourcePaths(List<String> sourcePaths) {
        this.sourcePaths = sourcePaths == null ? new ArrayList<>() : sourcePaths;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions == null ? new ArrayList<>() : allowedExtensions;
    }
}
