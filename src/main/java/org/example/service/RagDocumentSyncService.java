package org.example.service;

import org.example.config.RagSyncProperties;
import org.example.service.RagDocumentIndexStore.RagDocumentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RagDocumentSyncService {

    private static final Logger logger = LoggerFactory.getLogger(RagDocumentSyncService.class);
    private static final Logger syncLogger = LoggerFactory.getLogger("ai.rag.sync");

    private final RagSyncProperties properties;
    private final RagDocumentIndexStore indexStore;
    private final VectorIndexService vectorIndexService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RagDocumentSyncService(RagSyncProperties properties,
                                  RagDocumentIndexStore indexStore,
                                  VectorIndexService vectorIndexService) {
        this.properties = properties;
        this.indexStore = indexStore;
        this.vectorIndexService = vectorIndexService;
    }

    @Scheduled(fixedDelayString = "${rag.sync.interval-ms:60000}",
            initialDelayString = "${rag.sync.initial-delay-ms:30000}")
    public void syncConfiguredSources() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            logger.debug("RAG 文档同步仍在执行，跳过本轮");
            return;
        }
        try {
            syncNow();
        } finally {
            running.set(false);
        }
    }

    public SyncResult syncNow() {
        Instant startedAt = Instant.now();
        Map<String, RagDocumentRecord> records = indexStore.loadAll();
        Set<String> seenDocumentIds = new HashSet<>();
        SyncResult result = new SyncResult();

        for (String sourcePath : properties.getSourcePaths()) {
            Path root = Paths.get(sourcePath).normalize();
            if (!Files.exists(root)) {
                logger.warn("RAG 同步源不存在，跳过: {}", root);
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isAllowedFile)
                        .forEach(path -> syncFile(path.normalize(), records, seenDocumentIds, result));
            } catch (IOException e) {
                logger.warn("扫描 RAG 同步源失败: {}, error={}", root, e.getMessage());
            }
        }

        for (Map.Entry<String, RagDocumentRecord> entry : new LinkedHashMap<>(records).entrySet()) {
            RagDocumentRecord record = entry.getValue();
            if (record == null || !"active".equals(record.getStatus())) {
                continue;
            }
            if (!seenDocumentIds.contains(entry.getKey())) {
                long deleted = vectorIndexService.deleteDocumentVectors(record.getSourcePath());
                records.put(entry.getKey(), RagDocumentIndexStore.deleted(record));
                result.deleted++;
                logger.info("RAG 文档已删除并清理向量: documentId={}, deletedVectors={}",
                        entry.getKey(), deleted);
            }
        }

        indexStore.saveAll(records);
        result.durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        syncLogger.info(
                "event=rag_document_sync_completed scanned={} added={} updated={} deleted={} skipped={} failed={} durationMs={}",
                result.scanned, result.added, result.updated, result.deleted, result.skipped, result.failed,
                result.durationMs);
        return result;
    }

    public void syncSingleFile(Path filePath) {
        Map<String, RagDocumentRecord> records = indexStore.loadAll();
        SyncResult result = new SyncResult();
        syncFile(filePath.normalize(), records, new HashSet<>(), result);
        indexStore.saveAll(records);
    }

    private void syncFile(Path path,
                          Map<String, RagDocumentRecord> records,
                          Set<String> seenDocumentIds,
                          SyncResult result) {
        result.scanned++;
        String documentId = vectorIndexService.normalizePath(path.toString());
        seenDocumentIds.add(documentId);

        try {
            byte[] bytes = Files.readAllBytes(path);
            String contentHash = sha256(bytes);
            long lastModifiedAt = Files.getLastModifiedTime(path).toMillis();
            RagDocumentRecord previous = records.get(documentId);

            if (previous != null
                    && "active".equals(previous.getStatus())
                    && contentHash.equals(previous.getContentHash())) {
                result.skipped++;
                return;
            }

            String action = previous == null || "deleted".equals(previous.getStatus()) ? "added" : "updated";
            VectorIndexService.IndexedDocument indexed =
                    vectorIndexService.indexSingleFile(path.toString(), contentHash);
            records.put(documentId, RagDocumentIndexStore.active(
                    documentId,
                    indexed.documentId(),
                    fileName(path),
                    contentHash,
                    indexed.chunkCount(),
                    lastModifiedAt));
            if ("added".equals(action)) {
                result.added++;
            } else {
                result.updated++;
            }
            logger.info("RAG 文档{}完成: documentId={}, hash={}, chunks={}",
                    "added".equals(action) ? "新增索引" : "增量更新",
                    documentId,
                    contentHash,
                    indexed.chunkCount());
        } catch (Exception e) {
            result.failed++;
            String fileName = fileName(path);
            records.put(documentId, RagDocumentIndexStore.failed(
                    documentId,
                    documentId,
                    fileName,
                    "",
                    0L,
                    e.getMessage()));
            logger.warn("RAG 文档同步失败: documentId={}, error={}", documentId, e.getMessage());
        }
    }

    private boolean isAllowedFile(Path path) {
        String fileName = fileName(path).toLowerCase(Locale.ROOT);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dotIndex + 1);
        return properties.getAllowedExtensions().stream()
                .map(item -> item.toLowerCase(Locale.ROOT).trim())
                .anyMatch(extension::equals);
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    public static class SyncResult {
        private int scanned;
        private int added;
        private int updated;
        private int deleted;
        private int skipped;
        private int failed;
        private long durationMs;

        public int getScanned() {
            return scanned;
        }

        public int getAdded() {
            return added;
        }

        public int getUpdated() {
            return updated;
        }

        public int getDeleted() {
            return deleted;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getFailed() {
            return failed;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}
