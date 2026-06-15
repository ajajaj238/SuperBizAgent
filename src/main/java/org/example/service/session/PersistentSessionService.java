package org.example.service.session;

import jakarta.annotation.PreDestroy;
import org.example.config.SessionStorageProperties;
import org.example.entity.SessionIndex;
import org.example.entity.UserAccount;
import org.example.mapper.ConversationMessageMapper;
import org.example.mapper.SessionIndexMapper;
import org.example.mapper.UserAccountMapper;
import org.example.monitor.TokenEstimator;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class PersistentSessionService {

    private static final Logger logger = LoggerFactory.getLogger(PersistentSessionService.class);
    private static final Logger compressionLogger = LoggerFactory.getLogger("ai.memory.compression");
    private static final String DEFAULT_USERNAME = "default-user";

    private final UserAccountMapper userAccountMapper;
    private final SessionIndexMapper sessionIndexMapper;
    private final RedisSessionStore redisSessionStore;
    private final UserMemoryVectorStore userMemoryVectorStore;
    private final SessionStorageProperties storageProperties;
    private final ChatService chatService;
    private final ConversationMessageMapper conversationMessageMapper;
    private final PersonaExtractionService personaExtractionService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Set<String> compressingSessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService persistenceExecutor = new ThreadPoolExecutor(
            30,
            40,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final ExecutorService compressionExecutor = new ThreadPoolExecutor(
            2,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.AbortPolicy());

    public PersistentSessionService(UserAccountMapper userAccountMapper,
                                    SessionIndexMapper sessionIndexMapper,
                                    RedisSessionStore redisSessionStore,
                                    UserMemoryVectorStore userMemoryVectorStore,
                                    SessionStorageProperties storageProperties,
                                    ChatService chatService,
                                    ConversationMessageMapper conversationMessageMapper,
                                    PersonaExtractionService personaExtractionService,
                                    PasswordEncoder passwordEncoder,
                                    TransactionTemplate transactionTemplate) {
        this.userAccountMapper = userAccountMapper;
        this.sessionIndexMapper = sessionIndexMapper;
        this.redisSessionStore = redisSessionStore;
        this.userMemoryVectorStore = userMemoryVectorStore;
        this.storageProperties = storageProperties;
        this.chatService = chatService;
        this.conversationMessageMapper = conversationMessageMapper;
        this.personaExtractionService = personaExtractionService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
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
                    sessionContext.sessionId(),
                    currentQuestion,
                    storageProperties.getSemanticTopK()
            );
        } catch (Exception e) {
            logger.warn("读取会话 {} 的语义记忆失败，继续走短期上下文: {}", sessionContext.sessionId(), e.getMessage());
            return List.of();
        }
    }

    public Optional<String> getSessionSummary(SessionContext sessionContext) {
        try {
            return userMemoryVectorStore.getSessionSummary(
                    sessionContext.userId(), sessionContext.sessionId());
        } catch (Exception e) {
            logger.warn("读取会话 {} 的 Milvus 摘要失败: {}", sessionContext.sessionId(), e.getMessage());
            return Optional.empty();
        }
    }

    public int appendConversation(SessionContext sessionContext, String userQuestion, String aiAnswer, ChatService chatService) {
        ChatMessage userMsg = ChatMessage.user(userQuestion);
        ChatMessage assistantMsg = ChatMessage.assistant(aiAnswer);
        List<ChatMessage> compressionMessages = new ArrayList<>(redisSessionStore.getAllWarmMessages(
                sessionContext.userId(), sessionContext.sessionId()));
        compressionMessages.add(userMsg);
        compressionMessages.add(assistantMsg);

        // Redis 热消息同步写入，保证下一轮对话立刻能读到最近上下文。
        redisSessionStore.pushMessage(sessionContext.userId(), sessionContext.sessionId(), userMsg);
        redisSessionStore.pushMessage(sessionContext.userId(), sessionContext.sessionId(), assistantMsg);
        List<ChatMessage> warmMessages = redisSessionStore.getAllWarmMessages(
                sessionContext.userId(), sessionContext.sessionId());

        submitPersistenceTask(sessionContext, userQuestion, aiAnswer, userMsg, assistantMsg, warmMessages, compressionMessages);
        return Math.max(sessionContext.messagePairCount() + 1, Math.max(1, warmMessages.size() / 2));
    }

    private void submitPersistenceTask(SessionContext sessionContext,
                                       String userQuestion,
                                       String aiAnswer,
                                       ChatMessage userMsg,
                                       ChatMessage assistantMsg,
                                       List<ChatMessage> warmMessages,
                                       List<ChatMessage> compressionMessages) {
        persistenceExecutor.execute(() -> {
            try {
                synchronized (sessionContext.sessionId().intern()) {
                    PersistedConversation persisted = persistConversationToDatabase(
                            sessionContext, userQuestion, userMsg, assistantMsg);
                    submitPostProcessingTask(sessionContext, userQuestion, aiAnswer, warmMessages, compressionMessages, persisted);
                }
            } catch (Exception e) {
                logger.warn("异步保存会话失败: userId={}, sessionId={}, error={}",
                        sessionContext.userId(), sessionContext.sessionId(), e.getMessage(), e);
            }
        });
    }

    private void submitPostProcessingTask(SessionContext sessionContext,
                                          String userQuestion,
                                          String aiAnswer,
                                          List<ChatMessage> warmMessages,
                                          List<ChatMessage> compressionMessages,
                                          PersistedConversation persisted) {
        try {
            compressionExecutor.execute(() -> runAsyncPostProcessing(
                    sessionContext, userQuestion, aiAnswer, warmMessages, compressionMessages, persisted));
        } catch (RejectedExecutionException e) {
            logger.warn("会话压缩异步任务队列已满，跳过本轮后处理: userId={}, sessionId={}",
                    sessionContext.userId(), sessionContext.sessionId());
        }
    }

    private PersistedConversation persistConversationToDatabase(SessionContext sessionContext,
                                                               String userQuestion,
                                                               ChatMessage userMsg,
                                                               ChatMessage assistantMsg) {
        return transactionTemplate.execute(status -> {
            int currentIndex = conversationMessageMapper.getMaxIndex(sessionContext.sessionId());
            ChatMessage dbUserMsg = copyForDatabase(userMsg);
            dbUserMsg.setMsgIndex(currentIndex + 1);
            conversationMessageMapper.insert(sessionContext.sessionId(), dbUserMsg, currentIndex + 1);

            ChatMessage dbAssistantMsg = copyForDatabase(assistantMsg);
            dbAssistantMsg.setMsgIndex(currentIndex + 2);
            conversationMessageMapper.insert(sessionContext.sessionId(), dbAssistantMsg, currentIndex + 2);

            SessionIndex sessionIndex = sessionIndexMapper.findBySessionId(sessionContext.sessionId())
                    .orElseThrow(() -> new IllegalStateException("会话索引不存在: " + sessionContext.sessionId()));
            int nextPairCount = Optional.ofNullable(sessionIndex.getMessageCount()).orElse(0) + 1;
            sessionIndex.setMessageCount(nextPairCount);
            if (sessionIndex.getTitle() == null || sessionIndex.getTitle().isBlank()) {
                sessionIndex.setTitle(sanitizeForMysqlText(buildTitle(userQuestion)));
            }
            sessionIndex.setSummary(sanitizeForMysqlText(sessionIndex.getSummary()));
            sessionIndexMapper.update(sessionIndex);
            logger.info("会话异步落库完成: userId={}, sessionId={}, pairCount={}",
                    sessionContext.userId(), sessionContext.sessionId(), nextPairCount);
            return new PersistedConversation(sessionIndex, nextPairCount);
        });
    }

    private void runAsyncPostProcessing(SessionContext sessionContext,
                                        String userQuestion,
                                        String aiAnswer,
                                        List<ChatMessage> warmMessages,
                                        List<ChatMessage> compressionMessages,
                                        PersistedConversation persisted) {
        if (persisted == null || persisted.sessionIndex() == null) {
            return;
        }

        int nextPairCount = persisted.nextPairCount();
        SessionIndex sessionIndex = persisted.sessionIndex();
        CompressionDecision compressionDecision = shouldCompress(
                sessionContext.sessionId(), nextPairCount, userQuestion, aiAnswer, compressionMessages);
        if (compressionDecision.shouldCompress()) {
            if (!compressingSessions.add(sessionContext.sessionId())) {
                compressionLogger.info(
                        "event=session_compression_skipped sessionId={} reason=already_running pairCount={} warmMessages={} newMessages={}",
                        sessionContext.sessionId(),
                        nextPairCount,
                        compressionDecision.warmMessageCount(),
                        compressionDecision.newMessageCount());
            } else {
                try {
                    long startedAt = System.currentTimeMillis();
                    String previousSummary = sessionIndex.getSummary();
                    List<ChatMessage> incrementalMessages = tailMessages(compressionMessages, compressionDecision.newMessageCount());
                    int incrementalTokens = estimateMessagesTokens(incrementalMessages);
                    String summary = chatService.summarizeConversationMemory(previousSummary, toHistory(incrementalMessages));
                    int summaryTokens = TokenEstimator.estimateTextTokens(summary);
                    boolean milvusStored = true;
                    sessionIndex.setSummary(sanitizeForMysqlText(summary));
                    try {
                        userMemoryVectorStore.storeSessionSummary(sessionContext.userId(), sessionContext.sessionId(), summary);
                    } catch (Exception e) {
                        milvusStored = false;
                        logger.warn("会话 {} 写入 Milvus 语义记忆失败，已保留 MySQL 摘要: {}",
                                sessionContext.sessionId(), e.getMessage());
                    }
                    long durationMs = System.currentTimeMillis() - startedAt;
                    logger.info("会话 {} 已更新增量摘要，触发原因: {}, 新增消息数: {}, 增量token: {}",
                            sessionContext.sessionId(),
                            compressionDecision.reason(),
                            incrementalMessages.size(),
                            incrementalTokens);
                    compressionLogger.info(
                            "event=session_compression_completed userId={} sessionId={} reason={} pairCount={} warmMessages={} newMessages={} compressedMessages={} estimatedTokens={} incrementalTokens={} currentExchangeTokens={} tokenThreshold={} redisThreshold={} previousSummaryChars={} newSummaryChars={} newSummaryTokens={} compressionRatio={} durationMs={} milvusStored={}",
                            sessionContext.userId(),
                            sessionContext.sessionId(),
                            compressionDecision.reason(),
                            nextPairCount,
                            compressionDecision.warmMessageCount(),
                            compressionDecision.newMessageCount(),
                            incrementalMessages.size(),
                            compressionDecision.estimatedTokens(),
                            incrementalTokens,
                            compressionDecision.currentExchangeTokens(),
                            compressionDecision.tokenThreshold(),
                            compressionDecision.redisThreshold(),
                            textLength(previousSummary),
                            textLength(summary),
                            summaryTokens,
                            formatRatio(summaryTokens, incrementalTokens),
                            durationMs,
                            milvusStored);
                    transactionTemplate.executeWithoutResult(status -> sessionIndexMapper.update(sessionIndex));
                    redisSessionStore.markCompressed(sessionContext.sessionId(), nextPairCount);
                } finally {
                    compressingSessions.remove(sessionContext.sessionId());
                }
            }
        } else {
            compressionLogger.debug(
                    "event=session_compression_skipped sessionId={} reason={} pairCount={} warmMessages={} newMessages={} estimatedTokens={} currentExchangeTokens={} tokenThreshold={} redisThreshold={}",
                    sessionContext.sessionId(),
                    compressionDecision.reason(),
                    nextPairCount,
                    compressionDecision.warmMessageCount(),
                    compressionDecision.newMessageCount(),
                    compressionDecision.estimatedTokens(),
                    compressionDecision.currentExchangeTokens(),
                    compressionDecision.tokenThreshold(),
                    compressionDecision.redisThreshold());
        }

        triggerPersonaExtraction(sessionContext, nextPairCount, warmMessages);
    }

    /**
     * 分页加载会话消息
     * @param sessionId 会话ID
     * @param afterIndex 已加载的最大序号，从该序号之后加载
     * @param limit 每页条数
     */
    public List<ChatMessage> loadMessagesPage(String sessionId, int afterIndex, int limit) {
        return redisSessionStore.loadHistoryFromDb(sessionId, afterIndex, limit);
    }

    @Transactional
    public void clearSession(SessionContext sessionContext) {
        redisSessionStore.clearSession(sessionContext.userId(), sessionContext.sessionId());
        conversationMessageMapper.deleteBySessionId(sessionContext.sessionId());

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

    /**
     * 获取指定用户的所有会话列表（按创建时间倒序）
     */
    public List<SessionIndex> listUserSessions(Long userId) {
        if (userId == null) return List.of();
        List<SessionIndex> sessions = sessionIndexMapper.findByUserId(userId);
        sessions.removeIf(s -> s.getMessageCount() == null || s.getMessageCount() == 0);
        sessions.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return sessions;
    }

    @PreDestroy
    public void compressAllOnShutdown() {
        try {
            awaitPendingPersistenceTasks();
            List<Long> userIds = sessionIndexMapper.findDistinctUserIds();
            if (userIds.isEmpty()) {
                logger.info("关闭前压缩：没有需要处理的用户");
                return;
            }

            logger.info("关闭前压缩：开始处理 {} 个用户的所有会话", userIds.size());
            for (Long userId : userIds) {
                try {
                    compressUserSessions(userId);
                } catch (Exception e) {
                    logger.warn("关闭前压缩用户 {} 会话失败: {}", userId, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("关闭前统一压缩失败: {}", e.getMessage());
        }
    }

    /**
     * 压缩指定用户的所有活跃会话摘要并存入 Milvus
     */
    public void compressUserSessions(Long userId) {
        try {
            List<SessionIndex> userSessions = sessionIndexMapper.findByUserId(userId);
            if (userSessions.isEmpty()) {
                logger.info("用户 {} 没有需要压缩的会话", userId);
                return;
            }

            logger.info("用户 {} 开始压缩 {} 个会话", userId, userSessions.size());

            for (SessionIndex sessionIndex : userSessions) {
                if (!compressingSessions.add(sessionIndex.getSessionId())) {
                    compressionLogger.info(
                            "event=session_compression_skipped sessionId={} reason=already_running pairCount={}",
                            sessionIndex.getSessionId(),
                            Optional.ofNullable(sessionIndex.getMessageCount()).orElse(0));
                    continue;
                }
                try {
                    int totalPairs = Optional.ofNullable(sessionIndex.getMessageCount()).orElse(0);
                    if (totalPairs == 0) {
                        continue;
                    }
                    int lastCompressedPairCount = redisSessionStore.getLastCompressedPairCount(sessionIndex.getSessionId());
                    int afterIndex = Math.max(0, lastCompressedPairCount * 2);
                    List<ChatMessage> messages = loadMessagesPage(
                            sessionIndex.getSessionId(),
                            afterIndex,
                            Math.max(1, (totalPairs - lastCompressedPairCount) * 2));
                    if (messages.isEmpty()) {
                        continue;
                    }

                    long startedAt = System.currentTimeMillis();
                    String previousSummary = sessionIndex.getSummary();
                    int sourceTokens = estimateMessagesTokens(messages);
                    String summary = chatService.summarizeConversationMemory(previousSummary, toHistory(messages));
                    int summaryTokens = TokenEstimator.estimateTextTokens(summary);
                    sessionIndex.setSummary(sanitizeForMysqlText(summary));
                    sessionIndexMapper.update(sessionIndex);
                    userMemoryVectorStore.storeSessionSummary(sessionIndex.getUserId(), sessionIndex.getSessionId(), summary);
                    redisSessionStore.markCompressed(sessionIndex.getSessionId(), totalPairs);
                    compressionLogger.info(
                            "event=session_compression_completed userId={} sessionId={} reason={} pairCount={} warmMessages={} newMessages={} estimatedTokens={} currentExchangeTokens={} tokenThreshold={} redisThreshold={} previousSummaryChars={} newSummaryChars={} newSummaryTokens={} compressionRatio={} durationMs={} milvusStored={}",
                            sessionIndex.getUserId(),
                            sessionIndex.getSessionId(),
                            "manual_or_shutdown",
                            totalPairs,
                            messages.size(),
                            messages.size(),
                            sourceTokens,
                            0,
                            storageProperties.getCompressionTokenThreshold(),
                            redisThreshold(),
                            textLength(previousSummary),
                            textLength(summary),
                            summaryTokens,
                            formatRatio(summaryTokens, sourceTokens),
                            System.currentTimeMillis() - startedAt,
                            true);
                    logger.info("用户 {} 会话 {} 摘要已保存到 Milvus", userId, sessionIndex.getSessionId());
                } catch (Exception e) {
                    logger.warn("用户 {} 会话 {} 压缩失败: {}", userId, sessionIndex.getSessionId(), e.getMessage());
                } finally {
                    compressingSessions.remove(sessionIndex.getSessionId());
                }
            }
            logger.info("用户 {} 所有会话压缩完成", userId);
        } catch (Exception e) {
            logger.warn("用户 {} 压缩失败: {}", userId, e.getMessage());
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
                    user.setPasswordHash(passwordEncoder.encode("AUTO_CREATED_USER_" + UUID.randomUUID()));
                    user.setDisplayName(username);
                    user.setRole("user");
                    user.setStatus(1);
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

    private CompressionDecision shouldCompress(String sessionId,
                                               int nextPairCount,
                                               String userQuestion,
                                               String aiAnswer,
                                               List<ChatMessage> warmMessages) {
        int warmMessageCount = warmMessages == null ? 0 : warmMessages.size();
        int estimatedTokens = estimateMessagesTokens(warmMessages);
        int currentExchangeTokens = TokenEstimator.estimateTextTokens(
                (userQuestion == null ? "" : userQuestion) + "\n" + (aiAnswer == null ? "" : aiAnswer));
        int lastCompressedPairCount = redisSessionStore.getLastCompressedPairCount(sessionId);
        int newMessageCount = Math.max(0, nextPairCount - lastCompressedPairCount) * 2;
        int minMessages = Math.max(1, storageProperties.getCompressionMinMessages());
        int tokenThreshold = storageProperties.getCompressionTokenThreshold();
        int redisThreshold = redisThreshold();
        //第一层：根据token上限判断是否压缩
        if (tokenThreshold > 0
                && (currentExchangeTokens >= tokenThreshold || (estimatedTokens >= tokenThreshold && newMessageCount >= minMessages))) {
            return new CompressionDecision(true, "token_budget", warmMessageCount, estimatedTokens,
                    currentExchangeTokens, newMessageCount, tokenThreshold, redisThreshold);
        }

        //第二层：Redis最多保留10轮，达到第11轮时触发增量压缩。
        if (newMessageCount >= redisThreshold
                && newMessageCount >= minMessages) {
            return new CompressionDecision(true, "redis_round_limit", warmMessageCount, estimatedTokens,
                    currentExchangeTokens, newMessageCount, tokenThreshold, redisThreshold);
        }

        return new CompressionDecision(false, "skip", warmMessageCount, estimatedTokens,
                currentExchangeTokens, newMessageCount, tokenThreshold, redisThreshold);
    }

    private List<ChatMessage> tailMessages(List<ChatMessage> messages, int count) {
        if (messages == null || messages.isEmpty() || count <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, messages.size() - count);
        return new ArrayList<>(messages.subList(fromIndex, messages.size()));
    }

    private int estimateMessagesTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getRole() != null) {
                text.append(message.getRole()).append(": ");
            }
            if (message.getContent() != null) {
                text.append(message.getContent());
            }
            text.append('\n');
        }
        return TokenEstimator.estimateTextTokens(text.toString());
    }

    private int redisThreshold() {
        return Math.max(1, storageProperties.getRedisMaxMessages() + 2);
    }

    private ChatMessage copyForDatabase(ChatMessage source) {
        ChatMessage copy = new ChatMessage();
        copy.setMsgId(source.getMsgId());
        copy.setRole(source.getRole());
        copy.setContent(sanitizeForMysqlText(source.getContent()));
        copy.setTimestamp(source.getTimestamp());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setMsgIndex(source.getMsgIndex());
        return copy;
    }

    private String sanitizeForMysqlText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder builder = null;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            boolean supportedByMysqlUtf8 = codePoint <= 0xFFFF && codePoint != 0;
            if (!supportedByMysqlUtf8 && builder == null) {
                builder = new StringBuilder(text.length());
                builder.append(text, 0, i);
            }
            if (supportedByMysqlUtf8 && builder != null) {
                builder.appendCodePoint(codePoint);
            }
            i += charCount;
        }
        return builder == null ? text : builder.toString();
    }

    private void triggerPersonaExtraction(SessionContext sessionContext,
                                          int nextPairCount,
                                          List<ChatMessage> warmMessages) {
        try {
            int lastExtractedPairCount = redisSessionStore.getLastPersonaExtractedPairCount(sessionContext.sessionId());
            PersonaExtractionService.ExtractionResult result = personaExtractionService.extractIfNeeded(
                    sessionContext.userId(),
                    sessionContext.sessionId(),
                    nextPairCount,
                    lastExtractedPairCount,
                    warmMessages,
                    storageProperties.getCompressionTokenThreshold(),
                    storageProperties.getLlmContextWindow());
            if (result.attempted()) {
                redisSessionStore.markPersonaExtracted(sessionContext.sessionId(), nextPairCount);
            }
        } catch (Exception e) {
            logger.warn("用户画像抽取失败，已跳过本轮: userId={}, sessionId={}, error={}",
                    sessionContext.userId(), sessionContext.sessionId(), e.getMessage());
        }
    }

    private void awaitPendingPersistenceTasks() {
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(20, TimeUnit.SECONDS)) {
                List<Runnable> droppedTasks = persistenceExecutor.shutdownNow();
                logger.warn("关闭前仍有 {} 个会话异步保存任务未完成", droppedTasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<Runnable> droppedTasks = persistenceExecutor.shutdownNow();
            logger.warn("等待会话异步保存任务完成时被中断，未完成任务数: {}", droppedTasks.size());
        }
        compressionExecutor.shutdown();
        try {
            if (!compressionExecutor.awaitTermination(20, TimeUnit.SECONDS)) {
                List<Runnable> droppedTasks = compressionExecutor.shutdownNow();
                logger.warn("关闭前仍有 {} 个会话压缩任务未完成", droppedTasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<Runnable> droppedTasks = compressionExecutor.shutdownNow();
            logger.warn("等待会话压缩任务完成时被中断，未完成任务数: {}", droppedTasks.size());
        }
    }

    private int textLength(String text) {
        return text == null ? 0 : text.length();
    }

    private String formatRatio(int compressedTokens, int originalTokens) {
        if (originalTokens <= 0) {
            return "0.000";
        }
        return "%.3f".formatted((double) compressedTokens / originalTokens);
    }

    private record CompressionDecision(boolean shouldCompress,
                                       String reason,
                                       int warmMessageCount,
                                       int estimatedTokens,
                                       int currentExchangeTokens,
                                       int newMessageCount,
                                       int tokenThreshold,
                                       int redisThreshold) {
    }

    private record PersistedConversation(SessionIndex sessionIndex, int nextPairCount) {
    }
}
