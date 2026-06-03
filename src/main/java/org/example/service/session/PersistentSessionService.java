package org.example.service.session;

import jakarta.annotation.PreDestroy;
import org.example.config.SessionStorageProperties;
import org.example.entity.SessionIndex;
import org.example.entity.UserAccount;
import org.example.mapper.SessionIndexMapper;
import org.example.mapper.UserAccountMapper;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PersistentSessionService {

    private static final Logger logger = LoggerFactory.getLogger(PersistentSessionService.class);
    private static final String DEFAULT_USERNAME = "default-user";

    private final UserAccountMapper userAccountMapper;
    private final SessionIndexMapper sessionIndexMapper;
    private final RedisSessionStore redisSessionStore;
    private final FileSessionStore fileSessionStore;
    private final UserMemoryVectorStore userMemoryVectorStore;
    private final SessionStorageProperties storageProperties;
    private final ChatService chatService;

    public PersistentSessionService(UserAccountMapper userAccountMapper,
                                    SessionIndexMapper sessionIndexMapper,
                                    RedisSessionStore redisSessionStore,
                                    FileSessionStore fileSessionStore,
                                    UserMemoryVectorStore userMemoryVectorStore,
                                    SessionStorageProperties storageProperties,
                                    ChatService chatService) {
        this.userAccountMapper = userAccountMapper;
        this.sessionIndexMapper = sessionIndexMapper;
        this.redisSessionStore = redisSessionStore;
        this.fileSessionStore = fileSessionStore;
        this.userMemoryVectorStore = userMemoryVectorStore;
        this.storageProperties = storageProperties;
        this.chatService = chatService;
    }

    @Transactional
    public SessionContext getOrCreateSession(String requestedSessionId, String username) {
        UserAccount user = getOrCreateUser(username);
        String sessionId = (requestedSessionId == null || requestedSessionId.isBlank())
                ? UUID.randomUUID().toString()
                : requestedSessionId;

        SessionIndex sessionIndex = sessionIndexMapper.findBySessionId(sessionId)
                .orElseGet(() -> createSessionIndex(user.getId(), sessionId));
        redisSessionStore.bindSessionToUser(user.getId(), sessionId);

        return new SessionContext(
                user.getId(),
                user.getUsername(),
                sessionIndex.getSessionId(),
                sessionIndex.getCreatedAt(),
                Optional.ofNullable(sessionIndex.getMessageCount()).orElse(0)
        );
    }

    public List<Map<String, String>> getRecentHistory(SessionContext sessionContext) {
        List<ChatMessage> messages = redisSessionStore.getRecentMessages(
                sessionContext.userId(),
                sessionContext.sessionId(),
                storageProperties.getLlmContextWindow()
        );
        return toHistory(messages);
    }

    public List<ChatMessage> getAllWarmMessages(SessionContext sessionContext) {
        return redisSessionStore.getAllWarmMessages(sessionContext.userId(), sessionContext.sessionId());
    }

    public List<String> getSemanticMemories(SessionContext sessionContext, String currentQuestion) {
        try {
            return userMemoryVectorStore.searchRelevantMemories(
                    sessionContext.userId(),
                    currentQuestion,
                    storageProperties.getSemanticTopK()
            );
        } catch (Exception e) {
            logger.warn("读取会话 {} 的语义记忆失败，继续走短期上下文: {}", sessionContext.sessionId(), e.getMessage());
            return List.of();
        }
    }

    @Transactional
    public int appendConversation(SessionContext sessionContext, String userQuestion, String aiAnswer, ChatService chatService) {
        redisSessionStore.pushMessage(sessionContext.userId(), sessionContext.sessionId(), ChatMessage.user(userQuestion));
        redisSessionStore.pushMessage(sessionContext.userId(), sessionContext.sessionId(), ChatMessage.assistant(aiAnswer));

        SessionIndex sessionIndex = sessionIndexMapper.findBySessionId(sessionContext.sessionId())
                .orElseThrow(() -> new IllegalStateException("会话索引不存在: " + sessionContext.sessionId()));
        int nextPairCount = Optional.ofNullable(sessionIndex.getMessageCount()).orElse(0) + 1;
        sessionIndex.setMessageCount(nextPairCount);
        if (sessionIndex.getTitle() == null || sessionIndex.getTitle().isBlank()) {
            sessionIndex.setTitle(buildTitle(userQuestion));
        }

        if (nextPairCount % storageProperties.getCompressionInterval() == 0) {
            String summary = chatService.summarizeConversationMemory(toHistory(
                    redisSessionStore.getAllWarmMessages(sessionContext.userId(), sessionContext.sessionId())
            ));
            sessionIndex.setSummary(summary);
            try {
                userMemoryVectorStore.storeSessionSummary(sessionContext.userId(), sessionContext.sessionId(), summary);
            } catch (Exception e) {
                logger.warn("会话 {} 写入 Milvus 语义记忆失败，已保留 MySQL 摘要: {}",
                        sessionContext.sessionId(), e.getMessage());
            }
            logger.info("会话 {} 已更新持久化摘要", sessionContext.sessionId());
        }

        sessionIndexMapper.update(sessionIndex);
        return nextPairCount;
    }

    @Transactional
    public void clearSession(SessionContext sessionContext) {
        redisSessionStore.clearSession(sessionContext.userId(), sessionContext.sessionId());
        fileSessionStore.replaceMessages(sessionContext.userId(), sessionContext.sessionId(), List.of());

        sessionIndexMapper.findBySessionId(sessionContext.sessionId()).ifPresent(index -> {
            index.setMessageCount(0);
            index.setSummary(null);
            sessionIndexMapper.update(index);
        });
        userMemoryVectorStore.clearSessionMemories(sessionContext.userId(), sessionContext.sessionId());
    }

    public Optional<SessionContext> findSession(String sessionId) {
        return sessionIndexMapper.findBySessionId(sessionId)
                .map(index -> {
                    UserAccount user = userAccountMapper.findById(index.getUserId()).orElse(null);
                    return new SessionContext(
                            index.getUserId(),
                            user == null ? DEFAULT_USERNAME : user.getUsername(),
                            index.getSessionId(),
                            index.getCreatedAt(),
                            Optional.ofNullable(index.getMessageCount()).orElse(0)
                    );
                });
    }

    @PreDestroy
    public void compressAllOnShutdown() {
        try {
            fileSessionStore.flushNow();
            List<SessionIndex> activeSessions = sessionIndexMapper.findActiveSessions();
            if (activeSessions.isEmpty()) {
                logger.info("关闭前压缩：没有需要处理的活跃会话");
                return;
            }

            logger.info("关闭前压缩：开始处理 {} 个活跃会话", activeSessions.size());
            for (SessionIndex sessionIndex : activeSessions) {
                try {
                    List<ChatMessage> messages = fileSessionStore.readMessages(sessionIndex.getUserId(), sessionIndex.getSessionId());
                    if (messages.isEmpty()) {
                        messages = redisSessionStore.getAllWarmMessages(sessionIndex.getUserId(), sessionIndex.getSessionId());
                    }
                    if (messages.isEmpty()) {
                        continue;
                    }

                    String summary = chatService.summarizeConversationMemory(toHistory(messages));
                    sessionIndex.setSummary(summary);
                    sessionIndexMapper.update(sessionIndex);
                    userMemoryVectorStore.storeSessionSummary(sessionIndex.getUserId(), sessionIndex.getSessionId(), summary);
                    logger.info("关闭前压缩完成: sessionId={}", sessionIndex.getSessionId());
                } catch (Exception e) {
                    logger.warn("关闭前压缩会话失败: sessionId={}, error={}", sessionIndex.getSessionId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("关闭前统一压缩失败: {}", e.getMessage());
        }
    }

    private UserAccount getOrCreateUser(String requestedUsername) {
        String username = (requestedUsername == null || requestedUsername.isBlank())
                ? DEFAULT_USERNAME
                : requestedUsername.trim();

        return userAccountMapper.findByUsername(username)
                .orElseGet(() -> {
                    UserAccount user = new UserAccount();
                    user.setUsername(username);
                    user.setDisplayName(username);
                    userAccountMapper.insert(user);
                    return user;
                });
    }

    private SessionIndex createSessionIndex(Long userId, String sessionId) {
        SessionIndex sessionIndex = new SessionIndex();
        sessionIndex.setUserId(userId);
        sessionIndex.setSessionId(sessionId);
        sessionIndex.setStatus(1);
        sessionIndex.setMessageCount(0);
        sessionIndexMapper.insert(sessionIndex);
        return sessionIndex;
    }

    private String buildTitle(String question) {
        if (question == null || question.isBlank()) {
            return "新会话";
        }
        String normalized = question.replaceAll("\\s+", " ").trim();
        return normalized.length() > 30 ? normalized.substring(0, 30) + "..." : normalized;
    }

    private List<Map<String, String>> toHistory(List<ChatMessage> messages) {
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, String> item = new HashMap<>();
            item.put("role", message.getRole());
            item.put("content", message.getContent());
            history.add(item);
        }
        return history;
    }
}
