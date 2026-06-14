package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.example.config.ModelRoutingProperties;
import org.example.service.intent.UserIntent;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ModelRoutingService {

    private final ModelRoutingProperties properties;

    public ModelRoutingService(ModelRoutingProperties properties) {
        this.properties = properties;
    }

    public ModelSpec forIntent(UserIntent intent) {
        String key = intent == null ? "ambiguous" : toKebabCase(intent.name());
        return spec(resolveTier(properties.getIntent().get(key), defaultTierForIntent(intent)));
    }

    public ModelSpec forTask(ModelTask task) {
        String key = task == null ? "" : toKebabCase(task.name());
        return spec(resolveTier(properties.getTask().get(key), defaultTierForTask(task)));
    }

    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, ModelSpec spec) {
        ModelSpec safeSpec = spec == null ? forTask(ModelTask.DEFAULT_CHAT) : spec;
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(safeSpec.modelName())
                        .withTemperature(safeSpec.temperature())
                        .withMaxToken(safeSpec.maxToken())
                        .withTopP(safeSpec.topP())
                        .build())
                .build();
    }

    private Tier defaultTierForIntent(UserIntent intent) {
        if (intent == null) {
            return Tier.BALANCED;
        }
        return switch (intent) {
            case CHITCHAT, TIME_QUERY, SYSTEM_OPERATION -> Tier.FAST;
            case ALERT_DIAGNOSIS -> Tier.REASONING;
            case KNOWLEDGE_QA, LOG_QUERY, METRICS_QUERY, AMBIGUOUS -> Tier.BALANCED;
        };
    }

    private Tier defaultTierForTask(ModelTask task) {
        if (task == null) {
            return Tier.BALANCED;
        }
        return switch (task) {
            case INTENT_CLASSIFICATION, QUERY_REWRITE, PERSONA_EXTRACTION -> Tier.FAST;
            case MEMORY_SUMMARY, DEFAULT_CHAT -> Tier.BALANCED;
            case AIOPS_REPORT -> Tier.REASONING;
        };
    }

    private ModelSpec spec(Tier tier) {
        Tier safeTier = tier == null ? Tier.BALANCED : tier;
        ModelRoutingProperties.ModelOptions options = switch (safeTier) {
            case FAST -> properties.getFast();
            case BALANCED -> properties.getBalanced();
            case REASONING -> properties.getReasoning();
        };
        String modelName = switch (safeTier) {
            case FAST -> properties.getFastModel();
            case BALANCED -> properties.getBalancedModel();
            case REASONING -> properties.getReasoningModel();
        };
        return new ModelSpec(
                safeTier,
                modelName,
                options.getTemperature(),
                options.getMaxToken(),
                options.getTopP());
    }

    private Tier resolveTier(String configured, Tier fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            return Tier.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private String toKebabCase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public enum Tier {
        FAST,
        BALANCED,
        REASONING
    }

    public enum ModelTask {
        DEFAULT_CHAT,
        INTENT_CLASSIFICATION,
        QUERY_REWRITE,
        PERSONA_EXTRACTION,
        MEMORY_SUMMARY,
        AIOPS_REPORT
    }

    public record ModelSpec(Tier tier, String modelName, double temperature, int maxToken, double topP) {
    }
}
