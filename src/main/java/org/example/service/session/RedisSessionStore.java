package org.example.service.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.SessionStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class RedisSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisSessionStore.class);
    private static final TypeReference<ChatMessage> CHAT_MESSAGE_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FileSessionStore fileSessionStore;
    private final SessionStorageProperties storageProperties;

    public RedisSessionStore(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             FileSessionStore fileSessionStore,
                             SessionStorageProperties storageProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.fileSessionStore = fileSessionStore;
        this.storageProperties = storageProperties;
    }

    public void bindSessionToUser(Long userId, String sessionId) {
        redisTemplate.opsForSet().add(userSessionsKey(userId), sessionId);
        redisTemplate.expire(userSessionsKey(userId), storageProperties.getRedisTtl());
    }

    public void pushMessage(Long userId, String sessionId, ChatMessage msg) {
        String key = sessionKey(sessionId);
        redisTemplate.opsForList().rightPush(key, toJson(msg));
        redisTemplate.opsForList().trim(key, -storageProperties.getRedisMaxMessages(), -1);
        redisTemplate.expire(key, storageProperties.getRedisTtl());
        bindSessionToUser(userId, sessionId);
        fileSessionStore.appendMessageAsync(userId, sessionId, msg);
    }

    public List<ChatMessage> getRecentMessages(Long userId, String sessionId, int n) {
        String key = sessionKey(sessionId);
        List<String> raw = redisTemplate.opsForList().range(key, -n, -1);
        if (raw != null && !raw.isEmpty()) {
            return parse(raw);
        }
        return reloadFromJson(userId, sessionId);
    }

    public List<ChatMessage> getAllWarmMessages(Long userId, String sessionId) {
        String key = sessionKey(sessionId);
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw != null && !raw.isEmpty()) {
            return parse(raw);
        }
        return reloadFromJson(userId, sessionId);
    }

    public void clearSession(Long userId, String sessionId) {
        redisTemplate.delete(sessionKey(sessionId));
        redisTemplate.opsForSet().remove(userSessionsKey(userId), sessionId);
    }

    private List<ChatMessage> reloadFromJson(Long userId, String sessionId) {
        List<ChatMessage> all = fileSessionStore.readMessages(userId, sessionId);
        if (all.isEmpty()) {
            return List.of();
        }

        int maxMessages = storageProperties.getRedisMaxMessages();
        List<ChatMessage> recent = all.size() > maxMessages
                ? all.subList(all.size() - maxMessages, all.size())
                : all;

        String key = sessionKey(sessionId);
        for (ChatMessage message : recent) {
            redisTemplate.opsForList().rightPush(key, toJson(message));
        }
        redisTemplate.expire(key, storageProperties.getRedisTtl());
        logger.info("Redis 会话 {} 未命中，已从 JSON 恢复 {} 条消息", sessionId, recent.size());
        return new ArrayList<>(recent);
    }

    private List<ChatMessage> parse(List<String> raw) {
        List<ChatMessage> messages = new ArrayList<>();
        for (String item : raw) {
            try {
                messages.add(objectMapper.readValue(item, CHAT_MESSAGE_TYPE));
            } catch (JsonProcessingException e) {
                logger.warn("解析 Redis 消息失败: {}", e.getMessage());
            }
        }
        return messages;
    }

    private String toJson(ChatMessage msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化消息失败", e);
        }
    }

    private String sessionKey(String sessionId) {
        return "session:%s:messages".formatted(sessionId);
    }

    private String userSessionsKey(Long userId) {
        return "user:%s:sessions".formatted(userId);
    }
}
