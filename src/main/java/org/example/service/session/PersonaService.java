package org.example.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.PostConstruct;
import org.example.config.SessionStorageProperties;
import org.example.entity.UserPersona;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * 用户画像服务
 * - JSON 文件持久化（data/personas/{userId}.json）
 * - Caffeine 本地缓存（10min 过期）
 * - 启动时预加载所有画像
 */
@Service
public class PersonaService {

    private static final Logger logger = LoggerFactory.getLogger(PersonaService.class);

    private final Cache<Long, UserPersona> personaCache;
    private final ObjectMapper objectMapper;
    private final Path personaDir;

    public PersonaService(Cache<Long, UserPersona> personaCache,
                          ObjectMapper objectMapper,
                          SessionStorageProperties storageProperties) {
        this.personaCache = personaCache;
        this.objectMapper = objectMapper;
        this.personaDir = Paths.get(storageProperties.getPersonaPath());
    }

    @PostConstruct
    public void preload() {
        try {
            if (!Files.exists(personaDir)) {
                Files.createDirectories(personaDir);
                logger.info("用户画像目录已创建: {}", personaDir);
                return;
            }
            try (Stream<Path> files = Files.list(personaDir)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                    try {
                        UserPersona persona = objectMapper.readValue(file.toFile(), UserPersona.class);
                        if (persona.getUserId() != null) {
                            personaCache.put(persona.getUserId(), persona);
                            logger.debug("预加载用户画像: userId={}", persona.getUserId());
                        }
                    } catch (IOException e) {
                        logger.warn("读取用户画像文件失败: {}", file);
                    }
                });
            }
            logger.info("用户画像预加载完成，缓存目录: {}", personaDir);
        } catch (IOException e) {
            logger.warn("用户画像预加载失败: {}", e.getMessage());
        }
    }

    /**
     * 获取用户画像，优先走 Caffeine 缓存
     */
    public UserPersona getPersona(Long userId) {
        if (userId == null) return null;
        UserPersona cached = personaCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        UserPersona persona = loadFromFile(userId);
        if (persona == null) {
            persona = createDefaultPersona(userId);
            savePersona(userId, persona);
        } else {
            personaCache.put(userId, persona);
        }
        return persona;
    }

    /**
     * 保存或更新用户画像，同时写文件 + 更新缓存
     */
    public void savePersona(Long userId, UserPersona persona) {
        if (userId == null || persona == null) return;
        try {
            normalizeBeforeSave(userId, persona);
            Files.createDirectories(personaDir);
            Path file = personaDir.resolve(userId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), persona);
            personaCache.put(userId, persona);
            logger.info("用户画像已保存: userId={}", userId);
        } catch (IOException e) {
            logger.warn("保存用户画像失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 将用户画像格式化为 prompt 上下文块（空则返回空字符串）
     */
    public String buildPersonaPrompt(Long userId) {
        UserPersona persona = getPersona(userId);
        if (persona == null || persona.getPersona() == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n【用户画像，仅供静默参考】\n");
        sb.append("请只用这些信息调整回答风格和必要上下文，不要在回答中提到“用户画像”或显式复述画像来源。\n");

        var personaData = persona.getPersona();
        if (personaData.getOccupationRole() != null && !personaData.getOccupationRole().isEmpty()) {
            sb.append("角色: ");
            personaData.getOccupationRole().forEach(r -> sb.append(r.getValue()).append(" "));
            sb.append("\n");
        }
        if (personaData.getExpertiseDomains() != null && !personaData.getExpertiseDomains().isEmpty()) {
            sb.append("技术领域: ");
            personaData.getExpertiseDomains().forEach(d ->
                    sb.append(d.getDomain()).append("(").append(d.getLevel()).append(") "));
            sb.append("\n");
        }
        if (personaData.getPreferences() != null) {
            var pref = personaData.getPreferences();
            sb.append("偏好: ")
                    .append("语言=").append(pref.getPreferredLanguage())
                    .append(", 详细程度=").append(pref.getResponseVerbosity())
                    .append("\n");
        }

        return sb.toString();
    }

    private UserPersona loadFromFile(Long userId) {
        Path file = personaDir.resolve(userId + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), UserPersona.class);
        } catch (IOException e) {
            logger.warn("加载用户画像失败: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    private UserPersona createDefaultPersona(Long userId) {
        UserPersona persona = new UserPersona();
        persona.setUserId(userId);
        persona.setVersion(1);
        persona.setUpdatedAt(LocalDateTime.now());
        persona.setGeneratedBy("default");

        UserPersona.Persona data = new UserPersona.Persona();
        data.setOccupationRole(new ArrayList<>());
        data.setExpertiseDomains(new ArrayList<>());
        data.setFrequentActions(new ArrayList<>());

        UserPersona.Preferences preferences = new UserPersona.Preferences();
        preferences.setPreferredLanguage("zh-CN");
        preferences.setResponseVerbosity("normal");
        preferences.setFavoriteTools(new ArrayList<>());
        data.setPreferences(preferences);
        persona.setPersona(data);
        persona.setInferredFacts(new ArrayList<>());

        UserPersona.Provenance provenance = new UserPersona.Provenance();
        provenance.setTotalSessionsAnalyzed(0);
        provenance.setDataSources(List.of("default"));
        persona.setProvenance(provenance);
        return persona;
    }

    private void normalizeBeforeSave(Long userId, UserPersona persona) {
        persona.setUserId(userId);
        persona.setVersion(Math.max(1, persona.getVersion()));
        persona.setUpdatedAt(LocalDateTime.now());
        if (persona.getGeneratedBy() == null || persona.getGeneratedBy().isBlank()) {
            persona.setGeneratedBy("system");
        }
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
            UserPersona.Preferences preferences = new UserPersona.Preferences();
            preferences.setPreferredLanguage("zh-CN");
            preferences.setResponseVerbosity("normal");
            preferences.setFavoriteTools(new ArrayList<>());
            data.setPreferences(preferences);
        }
        if (persona.getInferredFacts() == null) {
            persona.setInferredFacts(new ArrayList<>());
        }
        if (persona.getProvenance() == null) {
            UserPersona.Provenance provenance = new UserPersona.Provenance();
            provenance.setTotalSessionsAnalyzed(0);
            provenance.setDataSources(new ArrayList<>());
            persona.setProvenance(provenance);
        }
    }
}
