package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Getter
@Configuration
@ConfigurationProperties(prefix = "ai.model-routing")
public class ModelRoutingProperties {

    private String fastModel = "qwen-turbo";
    private String balancedModel = "qwen-plus";
    private String reasoningModel = "qwen-plus";

    private ModelOptions fast = new ModelOptions(0.2, 512, 0.7);
    private ModelOptions balanced = new ModelOptions(0.5, 2000, 0.9);
    private ModelOptions reasoning = new ModelOptions(0.3, 8000, 0.8);

    private Map<String, String> intent = new HashMap<>();
    private Map<String, String> task = new HashMap<>();
    private Map<String, Integer> intentMaxToken = new HashMap<>();

    public void setFastModel(String fastModel) {
        this.fastModel = fastModel;
    }

    public void setBalancedModel(String balancedModel) {
        this.balancedModel = balancedModel;
    }

    public void setReasoningModel(String reasoningModel) {
        this.reasoningModel = reasoningModel;
    }

    public void setFast(ModelOptions fast) {
        this.fast = fast;
    }

    public void setBalanced(ModelOptions balanced) {
        this.balanced = balanced;
    }

    public void setReasoning(ModelOptions reasoning) {
        this.reasoning = reasoning;
    }

    public void setIntent(Map<String, String> intent) {
        this.intent = intent == null ? new HashMap<>() : intent;
    }

    public void setTask(Map<String, String> task) {
        this.task = task == null ? new HashMap<>() : task;
    }

    public void setIntentMaxToken(Map<String, Integer> intentMaxToken) {
        this.intentMaxToken = intentMaxToken == null ? new HashMap<>() : intentMaxToken;
    }

    @Getter
    public static class ModelOptions {
        private double temperature;
        private int maxToken;
        private double topP;

        public ModelOptions() {
        }

        public ModelOptions(double temperature, int maxToken, double topP) {
            this.temperature = temperature;
            this.maxToken = maxToken;
            this.topP = topP;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public void setMaxToken(int maxToken) {
            this.maxToken = maxToken;
        }

        public void setTopP(double topP) {
            this.topP = topP;
        }
    }
}
