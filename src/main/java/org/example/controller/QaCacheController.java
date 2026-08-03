package org.example.controller;

import org.example.service.QaCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * QA 答案缓存管理接口（测试/运维用）。
 * 清空缓存：切换 Redis 代次，O(1) 且不影响正在进行的请求。
 */
@RestController
@RequestMapping("/api/qa-cache")
public class QaCacheController {

    private static final Logger logger = LoggerFactory.getLogger(QaCacheController.class);

    private final QaCacheService qaCacheService;

    public QaCacheController(QaCacheService qaCacheService) {
        this.qaCacheService = qaCacheService;
    }

    /**
     * 清空 QA 答案缓存
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear() {
        qaCacheService.invalidateAll();
        logger.info("手动清空 QA 答案缓存");
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", "qa-cache cleared"
        ));
    }

    /**
     * 查看缓存统计（命中/未命中/写入/拦截/耗时）
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "success",
                "data", qaCacheService.stats()
        ));
    }
}
