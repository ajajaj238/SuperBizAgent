package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.RagSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RagDocumentIndexStore {

    private static final Logger logger = LoggerFactory.getLogger(RagDocumentIndexStore.class);
    private static final TypeReference<Map<String, RagDocumentRecord>> INDEX_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path indexFile;

    public RagDocumentIndexStore(ObjectMapper objectMapper, RagSyncProperties properties) {
        this.objectMapper = objectMapper;
        this.indexFile = Paths.get(properties.getIndexFile()).normalize();
    }

    public synchronized Map<String, RagDocumentRecord> loadAll() {
        if (!Files.exists(indexFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, RagDocumentRecord> records = objectMapper.readValue(indexFile.toFile(), INDEX_TYPE);
            return records == null ? new LinkedHashMap<>() : new LinkedHashMap<>(records);
        } catch (IOException e) {
            logger.warn("读取 RAG 文档索引失败，将使用空索引: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public synchronized void saveAll(Map<String, RagDocumentRecord> records) {
        try {
            Path parent = indexFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), records);
        } catch (IOException e) {
            logger.warn("保存 RAG 文档索引失败: {}", e.getMessage());
        }
    }

    public static RagDocumentRecord active(String documentId,
                                           String sourcePath,
                                           String fileName,
                                           String contentHash,
                                           int chunkCount,
                                           long lastModifiedAt) {
        RagDocumentRecord record = new RagDocumentRecord();
        record.setDocumentId(documentId);
        record.setSourcePath(sourcePath);
        record.setFileName(fileName);
        record.setContentHash(contentHash);
        record.setChunkCount(chunkCount);
        record.setStatus("active");
        record.setLastModifiedAt(lastModifiedAt);
        record.setLastIndexedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    public static RagDocumentRecord deleted(RagDocumentRecord previous) {
        RagDocumentRecord record = previous == null ? new RagDocumentRecord() : previous;
        record.setStatus("deleted");
        record.setChunkCount(0);
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    public static RagDocumentRecord failed(String documentId,
                                           String sourcePath,
                                           String fileName,
                                           String contentHash,
                                           long lastModifiedAt,
                                           String error) {
        RagDocumentRecord record = new RagDocumentRecord();
        record.setDocumentId(documentId);
        record.setSourcePath(sourcePath);
        record.setFileName(fileName);
        record.setContentHash(contentHash);
        record.setStatus("failed");
        record.setLastModifiedAt(lastModifiedAt);
        record.setLastError(error);
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }

    public static class RagDocumentRecord {
        private String documentId;
        private String sourcePath;
        private String fileName;
        private String contentHash;
        private int chunkCount;
        private String status;
        private long lastModifiedAt;
        private LocalDateTime lastIndexedAt;
        private LocalDateTime updatedAt;
        private String lastError;

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public void setSourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getContentHash() {
            return contentHash;
        }

        public void setContentHash(String contentHash) {
            this.contentHash = contentHash;
        }

        public int getChunkCount() {
            return chunkCount;
        }

        public void setChunkCount(int chunkCount) {
            this.chunkCount = chunkCount;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public long getLastModifiedAt() {
            return lastModifiedAt;
        }

        public void setLastModifiedAt(long lastModifiedAt) {
            this.lastModifiedAt = lastModifiedAt;
        }

        public LocalDateTime getLastIndexedAt() {
            return lastIndexedAt;
        }

        public void setLastIndexedAt(LocalDateTime lastIndexedAt) {
            this.lastIndexedAt = lastIndexedAt;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(String lastError) {
            this.lastError = lastError;
        }
    }
}
