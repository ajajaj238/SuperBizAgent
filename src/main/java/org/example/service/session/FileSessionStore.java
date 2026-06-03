package org.example.service.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.example.config.SessionStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class FileSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(FileSessionStore.class);
    private static final TypeReference<List<ChatMessage>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final SessionStorageProperties storageProperties;
    private final Queue<WriteTask> writeQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public FileSessionStore(ObjectMapper objectMapper, SessionStorageProperties storageProperties) {
        this.objectMapper = objectMapper;
        this.storageProperties = storageProperties;
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        flush();
        scheduler.shutdown();
    }

    public void flushNow() {
        flush();
    }

    public void appendMessageAsync(Long userId, String sessionId, ChatMessage msg) {
        writeQueue.add(new WriteTask(userId, sessionId, msg));
    }

    public List<ChatMessage> readMessages(Long userId, String sessionId) {
        Path file = messagesPath(userId, sessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(file.toFile(), MESSAGE_LIST_TYPE);
        } catch (IOException e) {
            throw new RuntimeException("读取会话文件失败: " + file, e);
        }
    }

    public void replaceMessages(Long userId, String sessionId, List<ChatMessage> messages) {
        Path file = messagesPath(userId, sessionId);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), messages);
        } catch (IOException e) {
            throw new RuntimeException("写入会话文件失败: " + file, e);
        }
    }

    private void flush() {
        try {
            Map<String, List<WriteTask>> grouped = new HashMap<>();
            WriteTask task;
            while ((task = writeQueue.poll()) != null) {
                grouped.computeIfAbsent(task.key(), ignored -> new ArrayList<>()).add(task);
            }

            for (List<WriteTask> tasks : grouped.values()) {
                WriteTask first = tasks.get(0);
                List<ChatMessage> existing = new ArrayList<>(readMessages(first.userId(), first.sessionId()));
                for (WriteTask item : tasks) {
                    existing.add(item.message());
                }
                replaceMessages(first.userId(), first.sessionId(), existing);
            }
        } catch (Exception e) {
            logger.warn("批量刷写会话文件失败: {}", e.getMessage());
        }
    }

    private Path messagesPath(Long userId, String sessionId) {
        return sessionDir(userId, sessionId).resolve("messages.json");
    }

    private Path sessionDir(Long userId, String sessionId) {
        return Paths.get(storageProperties.getPath(), String.valueOf(userId), sessionId);
    }

    private record WriteTask(Long userId, String sessionId, ChatMessage message) {
        String key() {
            return userId + ":" + sessionId;
        }
    }
}
