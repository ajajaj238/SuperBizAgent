package org.example.service.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.SessionStorageProperties;
import org.example.mapper.ConversationMessageMapper;
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
    private final SessionStorageProperties storageProperties;
    private final ConversationMessageMapper conversationMessageMapper;

    public RedisSessionStore(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             SessionStorageProperties storageProperties,
                             ConversationMessageMapper conversationMessageMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.storageProperties = storageProperties;
        this.conversationMessageMapper = conversationMessageMapper;
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
    }

    public List<ChatMessage> getRecentMessages(Long userId, String sessionId, int n) {
        String key = sessionKey(sessionId);
        //获取倒数第一条至倒数第n条消息
        List<String> raw = redisTemplate.opsForList().range(key, -n, -1);
        if (raw != null && !raw.isEmpty()) {
            return parse(raw);
        }
        //尝试从数据库加载
        return reloadFromDb(sessionId);
    }

    public List<ChatMessage> getAllWarmMessages(Long userId, String sessionId) {
        String key = sessionKey(sessionId);
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw != null && !raw.isEmpty()) {
            return parse(raw);
        }
        return reloadFromDb(sessionId);
    }

    public void clearSession(Long userId, String sessionId) {
        redisTemplate.delete(sessionKey(sessionId));
        redisTemplate.delete(compressionPairCountKey(sessionId));
        redisTemplate.delete(personaExtractionPairCountKey(sessionId));
        redisTemplate.opsForSet().remove(userSessionsKey(userId), sessionId);
    }

    public int getLastCompressedPairCount(String sessionId) {
        String value = redisTemplate.opsForValue().get(compressionPairCountKey(sessionId));
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("解析会话 {} 上次压缩轮数失败: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    public void markCompressed(String sessionId, int pairCount) {
        redisTemplate.opsForValue().set(
                compressionPairCountKey(sessionId),
                String.valueOf(pairCount),
                storageProperties.getRedisTtl());
    }

    public int getLastPersonaExtractedPairCount(String sessionId) {
        String value = redisTemplate.opsForValue().get(personaExtractionPairCountKey(sessionId));
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("解析会话 {} 上次画像抽取轮数失败: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    public void markPersonaExtracted(String sessionId, int pairCount) {
        redisTemplate.opsForValue().set(
                personaExtractionPairCountKey(sessionId),
                String.valueOf(pairCount),
                storageProperties.getRedisTtl());
    }

    /**
     * 从 MySQL 加载最近 N 条消息并回填 Redis
     */
    private List<ChatMessage> reloadFromDb(String sessionId) {
        int limit = storageProperties.getRedisMaxMessages();
        List<ChatMessage> recent = conversationMessageMapper.findRecentBySessionId(sessionId, 0, limit);
        if (recent.isEmpty()) {
            return List.of();
        }

        String key = sessionKey(sessionId);
        //保存redis
        for (ChatMessage message : recent) {
            redisTemplate.opsForList().rightPush(key, toJson(message));
        }
        redisTemplate.expire(key, storageProperties.getRedisTtl());
        logger.info("Redis 会话 {} 未命中，已从 MySQL 恢复 {} 条消息", sessionId, recent.size());
        return new ArrayList<>(recent);
    }

    /**
     * 分页加载历史消息（用户滚动加载）
     */
    public List<ChatMessage> loadHistoryFromDb(String sessionId, int afterIndex, int limit) {
        return conversationMessageMapper.findRecentBySessionId(sessionId, afterIndex, limit);
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

    private String compressionPairCountKey(String sessionId) {
        return "session:%s:compressed:pairs".formatted(sessionId);
    }

    private String personaExtractionPairCountKey(String sessionId) {
        return "session:%s:persona:extracted:pairs".formatted(sessionId);
    }
}
