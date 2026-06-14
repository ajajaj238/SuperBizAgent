package org.example.service.session;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.UserPersona;
import org.example.monitor.MonitoringChatModel;
import org.example.monitor.TokenEstimator;
import org.example.monitor.TokenUsageRecorder;
import org.example.service.ModelRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PersonaExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(PersonaExtractionService.class);
    private static final Logger personaLogger = LoggerFactory.getLogger("ai.persona.extraction");
    private static final Pattern ROLE_PATTERN = Pattern.compile(".*(?:我是|我是一名|我是一个|本人是|我做)([^，。,.\\s]{2,12}(?:工程师|开发|后端|前端|运维|测试|架构师|负责人|管理员|SRE)).*");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("(Prometheus|Kubernetes|K8s|Java|Spring Boot|MySQL|Redis|Milvus|Docker|日志|告警|监控|运维|AIOps)", Pattern.CASE_INSENSITIVE);
    private static final String SYSTEM_PROMPT = """
            你是用户画像抽取器。请从最近对话中抽取稳定、可跨会话复用的用户画像增量。

            只抽取这些类型：
            - occupationRole: 用户职业/职责/角色
            - expertiseDomains: 用户技术领域和熟悉程度
            - preferences: 明确表达的回答偏好
            - frequentActions: 用户常做或高频关注的操作
            - inferredFacts: 明确、稳定、可复用的事实

            不要抽取短期任务上下文，不要编造。
            如果没有值得更新的画像，hasUpdate=false。
            输出严格 JSON，不要输出 Markdown。

            JSON 格式：
            {
              "hasUpdate": true,
              "occupationRole": [{"value":"运维工程师","confidence":0.9,"source":"用户原话"}],
              "expertiseDomains": [{"domain":"Prometheus","level":"intermediate","confidence":0.8,"source":"用户原话"}],
              "preferences": {"preferredLanguage":"zh-CN","responseVerbosity":"concise","favoriteTools":["日志查询"]},
              "frequentActions": ["查询告警"],
              "inferredFacts": [{"fact":"用户负责 Prometheus 告警排查","confidence":0.8,"tags":["运维","监控"]}]
            }
            """;

    private final ObjectMapper objectMapper;
    private final PersonaService personaService;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final ModelRoutingService modelRoutingService;

    @Value("${persona.extraction.enabled:true}")
    private boolean enabled;

    @Value("${persona.extraction.llm-enabled:true}")
    private boolean llmEnabled;

    @Value("${persona.extraction.min-confidence:0.65}")
    private double minConfidence;

    @Value("${persona.extraction.max-analysis-messages:12}")
    private int maxAnalysisMessages;

    @Value("${persona.extraction.interval-rounds:5}")
    private int intervalRounds;

    @Value("${persona.extraction.min-new-messages:6}")
    private int minNewMessages;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    public PersonaExtractionService(ObjectMapper objectMapper,
                                    PersonaService personaService,
                                    TokenUsageRecorder tokenUsageRecorder,
                                    ModelRoutingService modelRoutingService) {
        this.objectMapper = objectMapper;
        this.personaService = personaService;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.modelRoutingService = modelRoutingService;
    }

    public ExtractionResult extractIfNeeded(Long userId,
                                            String sessionId,
                                            int pairCount,
                                            int lastExtractedPairCount,
                                            List<ChatMessage> warmMessages,
                                            int tokenThreshold,
                                            int windowMessageLimit) {
        if (!enabled || userId == null || sessionId == null || warmMessages == null || warmMessages.isEmpty()) {
            return ExtractionResult.skipped("disabled_or_empty");
        }

        List<ChatMessage> newMessages = sliceNewMessages(warmMessages, pairCount, lastExtractedPairCount);
        int estimatedTokens = estimateMessagesTokens(newMessages);
        boolean tokenTriggered = tokenThreshold > 0 && estimatedTokens >= tokenThreshold;
        boolean windowTriggered = windowMessageLimit > 0 && newMessages.size() >= windowMessageLimit;
        boolean periodicTriggered = intervalRounds > 0
                && pairCount > 0
                && pairCount % intervalRounds == 0
                && newMessages.size() >= Math.max(1, minNewMessages);
        if (!tokenTriggered && !windowTriggered && !periodicTriggered) {
            return ExtractionResult.skipped("below_threshold");
        }

        String reason = triggerReason(tokenTriggered, windowTriggered);
        List<ChatMessage> analysisMessages = tail(newMessages, Math.max(1, maxAnalysisMessages));
        boolean updated = extractAndMerge(userId, sessionId, analysisMessages, reason,
                estimatedTokens, newMessages.size());
        return new ExtractionResult(true, updated, reason, estimatedTokens, newMessages.size());
    }

    private String triggerReason(boolean tokenTriggered, boolean windowTriggered) {
        if (tokenTriggered) {
            return "token_budget";
        }
        if (windowTriggered) {
            return "context_window_limit";
        }
        return "periodic_check";
    }

    private boolean extractAndMerge(Long userId,
                                    String sessionId,
                                    List<ChatMessage> messages,
                                    String reason,
                                    int estimatedTokens,
                                    int newMessageCount) {
        UserPersona persona = personaService.getPersona(userId);
        JsonNode delta = null;
        String method = "llm";
        if (llmEnabled && dashScopeApiKey != null && !dashScopeApiKey.isBlank()) {
            try {
                delta = extractWithLlm(userId, sessionId, messages);
            } catch (Exception e) {
                logger.debug("LLM 用户画像抽取失败，使用规则兜底: {}", e.getMessage());
            }
        }
        if (delta == null) {
            method = "rule";
            delta = extractByRules(messages);
        }
        if (delta == null || !delta.path("hasUpdate").asBoolean(false)) {
            logCompleted(userId, sessionId, reason, method, false, List.of(), newMessageCount, estimatedTokens);
            return false;
        }

        List<String> changedFields = mergeDelta(persona, delta, sessionId, method);
        if (changedFields.isEmpty()) {
            logCompleted(userId, sessionId, reason, method, false, List.of(), newMessageCount, estimatedTokens);
            return false;
        }

        persona.setGeneratedBy(method);
        personaService.savePersona(userId, persona);
        logCompleted(userId, sessionId, reason, method, true, changedFields, newMessageCount, estimatedTokens);
        return true;
    }

    private JsonNode extractWithLlm(Long userId, String sessionId, List<ChatMessage> messages) throws Exception {
        ChatModel model = new MonitoringChatModel(createExtractionModel(), tokenUsageRecorder,
                modelRoutingService.forTask(ModelRoutingService.ModelTask.PERSONA_EXTRACTION).modelName());
        ChatResponse response = model.call(new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("最近对话：\n" + buildMessagesText(messages))
        )));
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
        personaLogger.info("event=persona_extraction_llm_raw userId={} sessionId={} response='{}'",
                userId, sessionId, normalize(text));
        return objectMapper.readTree(extractJson(text));
    }

    private void logCompleted(Long userId,
                              String sessionId,
                              String reason,
                              String method,
                              boolean updated,
                              List<String> fields,
                              int newMessageCount,
                              int estimatedTokens) {
        personaLogger.info(
                "event=persona_extraction_completed userId={} sessionId={} reason={} method={} updated={} fields={} newMessages={} estimatedTokens={}",
                userId, sessionId, reason, method, updated, fields, newMessageCount, estimatedTokens);
    }

    private DashScopeChatModel createExtractionModel() {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
        ModelRoutingService.ModelSpec spec =
                modelRoutingService.forTask(ModelRoutingService.ModelTask.PERSONA_EXTRACTION);
        spec = new ModelRoutingService.ModelSpec(spec.tier(), spec.modelName(), 0.1, 512, 0.5);
        return modelRoutingService.createChatModel(dashScopeApi, spec);
    }

    private JsonNode extractByRules(List<ChatMessage> messages) {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> domains = new LinkedHashSet<>();
        Set<String> actions = new LinkedHashSet<>();
        String preferredLanguage = "";
        String verbosity = "";

        for (ChatMessage message : messages) {
            if (message == null || !"user".equals(message.getRole()) || message.getContent() == null) {
                continue;
            }
            String content = normalize(message.getContent());
            Matcher roleMatcher = ROLE_PATTERN.matcher(content);
            if (roleMatcher.matches()) {
                roles.add(roleMatcher.group(1));
            }
            Matcher domainMatcher = DOMAIN_PATTERN.matcher(content);
            while (domainMatcher.find()) {
                domains.add(normalizeDomain(domainMatcher.group()));
            }
            if (content.contains("中文回答") || content.contains("用中文")) {
                preferredLanguage = "zh-CN";
            }
            if (content.contains("简短") || content.contains("简洁")) {
                verbosity = "concise";
            } else if (content.contains("详细") || content.contains("展开")) {
                verbosity = "detailed";
            }
            collectAction(content, actions);
        }

        boolean hasUpdate = !roles.isEmpty() || !domains.isEmpty() || !actions.isEmpty()
                || !preferredLanguage.isBlank() || !verbosity.isBlank();
        var root = objectMapper.createObjectNode();
        root.put("hasUpdate", hasUpdate);
        var roleArray = root.putArray("occupationRole");
        roles.forEach(role -> roleArray.addObject()
                .put("value", role)
                .put("confidence", 0.85)
                .put("source", "rule"));
        var domainArray = root.putArray("expertiseDomains");
        domains.forEach(domain -> domainArray.addObject()
                .put("domain", domain)
                .put("level", "intermediate")
                .put("confidence", 0.75)
                .put("source", "rule"));
        var preferences = root.putObject("preferences");
        if (!preferredLanguage.isBlank()) {
            preferences.put("preferredLanguage", preferredLanguage);
        }
        if (!verbosity.isBlank()) {
            preferences.put("responseVerbosity", verbosity);
        }
        root.putArray("frequentActions").addAll(actions.stream()
                .map(action -> objectMapper.getNodeFactory().textNode(action))
                .toList());
        return root;
    }

    private List<String> mergeDelta(UserPersona persona, JsonNode delta, String sessionId, String source) {
        ensureShape(persona);
        List<String> changed = new ArrayList<>();
        UserPersona.Persona data = persona.getPersona();

        if (mergeRoles(data.getOccupationRole(), delta.path("occupationRole"), source)) {
            changed.add("occupationRole");
        }
        if (mergeDomains(data.getExpertiseDomains(), delta.path("expertiseDomains"), source)) {
            changed.add("expertiseDomains");
        }
        if (mergePreferences(data.getPreferences(), delta.path("preferences"))) {
            changed.add("preferences");
        }
        if (mergeStringList(data.getFrequentActions(), delta.path("frequentActions"), 12)) {
            changed.add("frequentActions");
        }
        if (mergeFacts(persona.getInferredFacts(), delta.path("inferredFacts"), sessionId)) {
            changed.add("inferredFacts");
        }

        UserPersona.Provenance provenance = persona.getProvenance();
        provenance.setLastAnalysisSession(sessionId);
        provenance.setTotalSessionsAnalyzed(provenance.getTotalSessionsAnalyzed() + 1);
        if (provenance.getDataSources() == null) {
            provenance.setDataSources(new ArrayList<>());
        }
        if (!provenance.getDataSources().contains(source)) {
            provenance.getDataSources().add(source);
        }
        return changed;
    }

    private boolean mergeRoles(List<UserPersona.OccupationRole> roles, JsonNode nodes, String source) {
        boolean changed = false;
        if (!nodes.isArray()) {
            return false;
        }
        for (JsonNode node : nodes) {
            String value = normalize(node.path("value").asText(""));
            double confidence = node.path("confidence").asDouble(0.0);
            if (value.isBlank() || confidence < minConfidence) {
                continue;
            }
            UserPersona.OccupationRole existing = roles.stream()
                    .filter(item -> value.equalsIgnoreCase(item.getValue()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                UserPersona.OccupationRole role = new UserPersona.OccupationRole();
                role.setValue(value);
                role.setConfidence(confidence);
                role.setSource(source);
                roles.add(role);
                changed = true;
            } else if (confidence > existing.getConfidence()) {
                existing.setConfidence(confidence);
                existing.setSource(source);
                changed = true;
            }
        }
        return changed;
    }

    private boolean mergeDomains(List<UserPersona.ExpertiseDomain> domains, JsonNode nodes, String source) {
        boolean changed = false;
        if (!nodes.isArray()) {
            return false;
        }
        for (JsonNode node : nodes) {
            String domain = normalize(node.path("domain").asText(""));
            double confidence = node.path("confidence").asDouble(0.0);
            if (domain.isBlank() || confidence < minConfidence) {
                continue;
            }
            UserPersona.ExpertiseDomain existing = domains.stream()
                    .filter(item -> domain.equalsIgnoreCase(item.getDomain()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                UserPersona.ExpertiseDomain item = new UserPersona.ExpertiseDomain();
                item.setDomain(domain);
                item.setLevel(normalizeLevel(node.path("level").asText("intermediate")));
                item.setConfidence(confidence);
                item.setSource(source);
                domains.add(item);
                changed = true;
            } else if (confidence > existing.getConfidence()) {
                existing.setLevel(normalizeLevel(node.path("level").asText(existing.getLevel())));
                existing.setConfidence(confidence);
                existing.setSource(source);
                changed = true;
            }
        }
        return changed;
    }

    private boolean mergePreferences(UserPersona.Preferences preferences, JsonNode node) {
        if (!node.isObject()) {
            return false;
        }
        boolean changed = false;
        String language = normalize(node.path("preferredLanguage").asText(""));
        if (!language.isBlank() && !language.equals(preferences.getPreferredLanguage())) {
            preferences.setPreferredLanguage(language);
            changed = true;
        }
        String verbosity = normalize(node.path("responseVerbosity").asText(""));
        if (!verbosity.isBlank() && !verbosity.equals(preferences.getResponseVerbosity())) {
            preferences.setResponseVerbosity(verbosity);
            changed = true;
        }
        if (node.path("favoriteTools").isArray()) {
            if (preferences.getFavoriteTools() == null) {
                preferences.setFavoriteTools(new ArrayList<>());
            }
            changed = mergeStringList(preferences.getFavoriteTools(), node.path("favoriteTools"), 8) || changed;
        }
        return changed;
    }

    private boolean mergeStringList(List<String> target, JsonNode nodes, int limit) {
        if (!nodes.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode node : nodes) {
            String value = normalize(node.asText(""));
            if (value.isBlank() || containsIgnoreCase(target, value)) {
                continue;
            }
            target.add(value);
            changed = true;
            if (target.size() >= limit) {
                break;
            }
        }
        return changed;
    }

    private boolean mergeFacts(List<UserPersona.InferredFact> facts, JsonNode nodes, String sessionId) {
        boolean changed = false;
        if (!nodes.isArray()) {
            return false;
        }
        for (JsonNode node : nodes) {
            String fact = normalize(node.path("fact").asText(""));
            double confidence = node.path("confidence").asDouble(0.0);
            if (fact.isBlank() || confidence < minConfidence) {
                continue;
            }
            UserPersona.InferredFact existing = facts.stream()
                    .filter(item -> fact.equalsIgnoreCase(item.getFact()))
                    .findFirst()
                    .orElse(null);
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                UserPersona.InferredFact item = new UserPersona.InferredFact();
                item.setFact(fact);
                item.setConfidence(confidence);
                item.setSourceSession(sessionId);
                item.setFirstObserved(now);
                item.setLastObserved(now);
                item.setTags(readStringArray(node.path("tags")));
                facts.add(item);
                changed = true;
            } else {
                existing.setLastObserved(now);
                if (confidence > existing.getConfidence()) {
                    existing.setConfidence(confidence);
                }
                changed = true;
            }
        }
        return changed;
    }

    private void ensureShape(UserPersona persona) {
        if (persona.getPersona() == null) {
            persona.setPersona(new UserPersona.Persona());
        }
        UserPersona.Persona data = persona.getPersona();
        if (data.getOccupationRole() == null) {
            data.setOccupationRole(new ArrayList<>());
        }
        if (data.getExpertiseDomains() == null) {
            data.setExpertiseDomains(new ArrayList<>());
        }
        if (data.getFrequentActions() == null) {
            data.setFrequentActions(new ArrayList<>());
        }
        if (data.getPreferences() == null) {
            data.setPreferences(new UserPersona.Preferences());
        }
        if (data.getPreferences().getFavoriteTools() == null) {
            data.getPreferences().setFavoriteTools(new ArrayList<>());
        }
        if (persona.getInferredFacts() == null) {
            persona.setInferredFacts(new ArrayList<>());
        }
        if (persona.getProvenance() == null) {
            persona.setProvenance(new UserPersona.Provenance());
        }
    }

    private List<ChatMessage> sliceNewMessages(List<ChatMessage> warmMessages, int pairCount, int lastExtractedPairCount) {
        int newMessageCount = Math.max(0, pairCount - lastExtractedPairCount) * 2;
        if (newMessageCount <= 0) {
            return List.of();
        }
        return tail(warmMessages, newMessageCount);
    }

    private List<ChatMessage> tail(List<ChatMessage> messages, int limit) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, messages.size() - limit);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    private int estimateMessagesTokens(List<ChatMessage> messages) {
        return TokenEstimator.estimateTextTokens(buildMessagesText(messages));
    }

    private String buildMessagesText(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            builder.append("user".equals(message.getRole()) ? "用户" : "助手")
                    .append(": ")
                    .append(normalize(message.getContent()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private void collectAction(String content, Set<String> actions) {
        if (content.contains("查日志") || content.contains("查询日志")) {
            actions.add("查询日志");
        }
        if (content.contains("查告警") || content.contains("查询告警") || content.contains("告警")) {
            actions.add("查询告警");
        }
        if (content.contains("排查")) {
            actions.add("故障排查");
        }
        if (content.contains("CPU") || content.contains("内存") || content.contains("磁盘")) {
            actions.add("资源使用率排查");
        }
    }

    private List<String> readStringArray(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        if (!nodes.isArray()) {
            return values;
        }
        for (JsonNode node : nodes) {
            String value = normalize(node.asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private boolean containsIgnoreCase(List<String> values, String value) {
        return values.stream().anyMatch(item -> item.equalsIgnoreCase(value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String normalizeDomain(String value) {
        String normalized = normalize(value);
        if ("k8s".equalsIgnoreCase(normalized)) {
            return "Kubernetes";
        }
        return normalized;
    }

    private String normalizeLevel(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "expert", "beginner", "intermediate" -> normalized;
            default -> "intermediate";
        };
    }

    public record ExtractionResult(boolean attempted,
                                   boolean updated,
                                   String reason,
                                   int estimatedTokens,
                                   int newMessageCount) {
        public static ExtractionResult skipped(String reason) {
            return new ExtractionResult(false, false, reason, 0, 0);
        }
    }
}
