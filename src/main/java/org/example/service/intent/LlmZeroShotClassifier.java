package org.example.service.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ChatService;
import org.example.service.ModelRoutingService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LlmZeroShotClassifier implements IntentClassifier {

    private static final Logger logger = LoggerFactory.getLogger(LlmZeroShotClassifier.class);
    private static final String SYSTEM_PROMPT = """
            你是一个用户意图分类器。请从以下类别中选择最匹配用户意图的一项：

            KNOWLEDGE_QA - 询问内部知识库、SOP、处理方案、最佳实践
            ALERT_DIAGNOSIS - 要求分析监控告警、生成诊断报告
            LOG_QUERY - 要求查询日志
            METRICS_QUERY - 查询监控指标或告警状态
            TIME_QUERY - 询问当前时间或日期
            CHITCHAT - 闲聊、问候、不涉及工具调用的对话
            SYSTEM_OPERATION - 系统操作、会话管理
            AMBIGUOUS - 以上都不确定

            规则：
            - 优先选择 KNOWLEDGE_QA（用户大概率在问知识）
            - 只有明确提到“日志”或“log”时选择 LOG_QUERY
            - 只有明确提到“告警/指标/监控/使用率”时选择 METRICS_QUERY
            - 只有明确要求“分析/诊断/检查”系统时选择 ALERT_DIAGNOSIS

            输出严格 JSON 格式：
            {"intent":"INTENT_NAME","confidence":0.xx,"reason":"简短原因"}
            """;

    private final ChatService chatService;
    private final VectorSearchService vectorSearchService;
    private final ObjectMapper objectMapper;

    public LlmZeroShotClassifier(ChatService chatService,
                                 VectorSearchService vectorSearchService,
                                 ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.vectorSearchService = vectorSearchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntentResult classify(String userInput, List<Map<String, String>> history) {
        try {
            ChatModel model = chatService.createMonitoredChatModel(
                    chatService.createChatModelForTask(
                            chatService.createDashScopeApi(),
                            ModelRoutingService.ModelTask.INTENT_CLASSIFICATION),
                    chatService.modelSpecForTask(ModelRoutingService.ModelTask.INTENT_CLASSIFICATION));
            ChatResponse response = model.call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(buildFewShotPrompt(userInput))
            )));
            String text = response.getResult().getOutput().getText();
            return parseResponse(userInput, text);
        } catch (Exception e) {
            logger.warn("LLM 意图兜底分类失败: {}", e.getMessage());
            return IntentResult.builder()
                    .intent(UserIntent.AMBIGUOUS)
                    .confidence(0.0)
                    .method("llm_failed")
                    .reason(e.getMessage())
                    .rawInput(userInput)
                    .build();
        }
    }

    private String buildFewShotPrompt(String input) {
        StringBuilder sb = new StringBuilder("参考示例：\n");
        try {
            List<VectorSearchService.IntentSearchResult> examples =
                    vectorSearchService.searchIntentExamples(input, 3);
            for (VectorSearchService.IntentSearchResult ex : examples) {
                sb.append("输入：").append(ex.getExample()).append("\n");
                sb.append("意图：").append(ex.getIntent()).append("\n\n");
            }
        } catch (Exception e) {
            logger.debug("检索 few-shot 示例失败，继续零样本分类: {}", e.getMessage());
        }
        sb.append("用户输入：").append(input);
        return sb.toString();
    }

    private IntentResult parseResponse(String userInput, String responseText) throws Exception {
        String json = extractJson(responseText);
        JsonNode node = objectMapper.readTree(json);
        UserIntent intent = UserIntent.valueOf(node.path("intent").asText("AMBIGUOUS"));
        double confidence = node.path("confidence").asDouble(0.5);
        String reason = node.path("reason").asText("");
        return IntentResult.builder()
                .intent(intent)
                .confidence(Math.max(0.0, Math.min(1.0, confidence)))
                .method("llm")
                .reason(reason)
                .rawInput(userInput)
                .build();
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
