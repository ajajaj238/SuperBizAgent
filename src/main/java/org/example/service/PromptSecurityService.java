package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 安全服务
 * 在文本进入模型前做轻量的提示注入检测与清洗。
 */
@Service
public class PromptSecurityService {

    private static final Logger logger = LoggerFactory.getLogger(PromptSecurityService.class);

    private static final String FILTER_PLACEHOLDER = "[潜在提示注入内容已移除]";

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?previous\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ignore\\s+the\\s+above", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s*prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("developer\\s+message", Pattern.CASE_INSENSITIVE),
            Pattern.compile("tool\\s*call", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do not call tools?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("忽略(之前|前面|以上)?(所有)?指令"),
            Pattern.compile("你现在是"),
            Pattern.compile("系统提示词"),
            Pattern.compile("开发者消息"),
            Pattern.compile("不要调用工具"),
            Pattern.compile("输出.*(密钥|token|api\\s*key|secret)", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 对即将进入 Prompt 的文本做检测与清洗。
     */
    public String sanitizeForPrompt(String content, String source) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String sanitized = content;
        boolean suspicious = false;

        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(sanitized).find()) {
                suspicious = true;
                sanitized = pattern.matcher(sanitized).replaceAll(FILTER_PLACEHOLDER);
            }
        }

        if (suspicious) {
            logger.warn("检测到潜在 Prompt 注入内容，来源: {}", source);
        }

        return sanitized.trim();
    }
}
