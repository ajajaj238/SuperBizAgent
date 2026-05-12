package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.evaluation")
public class RagEvaluationProperties {

    private boolean enabled = false;

    private boolean logNoLabels = true;

    private String datasetLocation = "classpath:retrieval-eval.json";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogNoLabels() {
        return logNoLabels;
    }

    public void setLogNoLabels(boolean logNoLabels) {
        this.logNoLabels = logNoLabels;
    }

    public String getDatasetLocation() {
        return datasetLocation;
    }

    public void setDatasetLocation(String datasetLocation) {
        this.datasetLocation = datasetLocation;
    }
}
