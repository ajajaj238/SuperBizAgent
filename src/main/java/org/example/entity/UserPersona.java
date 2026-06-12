package org.example.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户画像（对应 data/personas/{userId}.json）
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPersona {
    private Long userId;
    private String tenantId;
    private int version;
    private LocalDateTime updatedAt;
    private String generatedBy;       // "llm" | "rule"
    private Persona persona;
    private List<InferredFact> inferredFacts;
    private Provenance provenance;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Persona {
        private List<OccupationRole> occupationRole;
        private List<ExpertiseDomain> expertiseDomains;
        private List<String> frequentActions;
        private Preferences preferences;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OccupationRole {
        private String value;
        private double confidence;
        private String source;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExpertiseDomain {
        private String domain;
        private String level;         // "expert" | "intermediate" | "beginner"
        private double confidence;
        private String source;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Preferences {
        private String responseVerbosity;
        private List<String> favoriteTools;
        private String uiTheme;
        private String preferredLanguage;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InferredFact {
        private String fact;
        private double confidence;
        private String sourceSession;
        private LocalDateTime firstObserved;
        private LocalDateTime lastObserved;
        private List<String> tags;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Provenance {
        private String lastAnalysisSession;
        private int totalSessionsAnalyzed;
        private List<String> dataSources;
    }
}
