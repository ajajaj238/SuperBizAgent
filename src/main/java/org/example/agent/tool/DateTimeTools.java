package org.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateTimeTools {
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_GET_CURRENT_DATETIME = "getCurrentDateTime";

    private static final DateTimeFormatter ZH_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");
    
    @Tool(description = "Get the current date and time in the user's timezone")
    public String getCurrentDateTime() {
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }

    /**
     * 返回格式化后的当前时间（中文，含时区），用于时间问题直接回答，避免 LLM 幻觉。
     */
    public String getCurrentDateTimeFormatted() {
        java.time.ZonedDateTime now = LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId());
        return now.format(ZH_FORMATTER) + "（" + LocaleContextHolder.getTimeZone().getID() + "）";
    }
}
