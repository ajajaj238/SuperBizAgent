package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.monitor.TokenUsageRecorder;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.intent.HybridIntentClassifier;
import org.example.service.intent.IntentResult;
import org.example.service.intent.SessionIntentTracker;
import org.example.service.intent.ToolFilter;
import org.example.service.intent.UserIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private ChatService chatService;

    @Autowired
    private TokenUsageRecorder tokenUsageRecorder;

    @Autowired
    private HybridIntentClassifier intentClassifier;

    @Autowired
    private SessionIntentTracker intentTracker;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    
    // 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对）
    private static final int MAX_WINDOW_SIZE = 10;

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            // 获取或创建会话
            SessionInfo session = getOrCreateSession(request.getId());
            tokenUsageRecorder.beginConversation(session.getSessionId(), "/api/chat", request.getQuestion());
            
            // 获取历史消息
            List<Map<String, String>> history = session.getHistory();
            logger.info("会话历史消息对数: {}", history.size() / 2);

            IntentResult intentResult = intentClassifier.classify(request.getQuestion(), session.getSessionId(), history);
            recordIntent(session.getSessionId(), intentResult);

            if (intentResult.getIntent() == UserIntent.SYSTEM_OPERATION) {
                String answer = handleSystemOperation(session);
                tokenUsageRecorder.completeConversationSuccess(answer.length());
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(answer)));
            }

            if (intentResult.getIntent() == UserIntent.ALERT_DIAGNOSIS) {
                String report = executeAiOpsReport();
                session.addMessage(request.getQuestion(), report, chatService);
                tokenUsageRecorder.completeConversationSuccess(report.length());
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(report)));
            }

            // 创建 DashScope API 和 ChatModel
            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            DashScopeChatModel chatModel = chatService.createChatModel(
                    dashScopeApi,
                    chatService.temperatureForIntent(intentResult.getIntent()),
                    2000,
                    0.9);

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            // 构建系统提示词（包含历史消息）
            String systemPrompt = intentResult.getIntent() == UserIntent.AMBIGUOUS
                    ? chatService.buildSystemPrompt(history)
                    : chatService.buildIntentSystemPrompt(intentResult.getIntent());
            var monitoredChatModel = chatService.createMonitoredChatModel(chatModel);
            
            // 创建 ReactAgent
            ToolFilter toolFilter = chatService.toolFilterForIntent(intentResult.getIntent());
            ReactAgent agent = chatService.createReactAgent(monitoredChatModel, systemPrompt, toolFilter);

            // 每次回答前先执行 RAG 预检索，再将检索结果注入问题上下文
            String enrichedQuestion = chatService.buildAgentUserPrompt(
                    history,
                    request.getQuestion(),
                    chatService.shouldEnableRag(intentResult.getIntent()));
            
            // 执行对话
            String fullAnswer = chatService.executeChat(agent, monitoredChatModel, systemPrompt, enrichedQuestion);
            
            // 更新会话历史
            session.addMessage(request.getQuestion(), fullAnswer, chatService);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                request.getId(), session.getMessagePairCount());
            tokenUsageRecorder.completeConversationSuccess(fullAnswer.length());

            //打印历史记录
            System.out.println();
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (Exception e) {
            tokenUsageRecorder.completeConversationError(e.getMessage());
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            SessionInfo session = sessions.get(request.getId());
            if (session != null) {
                session.clearHistory();
                intentTracker.clear(request.getId());
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话
                SessionInfo session = getOrCreateSession(request.getId());
                tokenUsageRecorder.beginConversation(session.getSessionId(), "/api/chat_stream", request.getQuestion());
                
                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                IntentResult intentResult = intentClassifier.classify(request.getQuestion(), session.getSessionId(), history);
                recordIntent(session.getSessionId(), intentResult);

                if (intentResult.getIntent() == UserIntent.SYSTEM_OPERATION) {
                    String answer = handleSystemOperation(session);
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.content(answer), MediaType.APPLICATION_JSON));
                    tokenUsageRecorder.completeConversationSuccess(answer.length());
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                if (intentResult.getIntent() == UserIntent.ALERT_DIAGNOSIS) {
                    streamAiOpsReport(emitter);
                    String answer = "已通过 AIOps 多 Agent 生成告警分析报告。";
                    session.addMessage(request.getQuestion(), answer, chatService);
                    tokenUsageRecorder.completeConversationSuccess(answer.length());
                    emitter.complete();
                    return;
                }

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createChatModel(
                        dashScopeApi,
                        chatService.temperatureForIntent(intentResult.getIntent()),
                        2000,
                        0.9);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = intentResult.getIntent() == UserIntent.AMBIGUOUS
                        ? chatService.buildSystemPrompt(history)
                        : chatService.buildIntentSystemPrompt(intentResult.getIntent());
                var monitoredChatModel = chatService.createMonitoredChatModel(chatModel);
                
                // 创建 ReactAgent
                ToolFilter toolFilter = chatService.toolFilterForIntent(intentResult.getIntent());
                ReactAgent agent = chatService.createReactAgent(monitoredChatModel, systemPrompt, toolFilter);

                // 每次回答前先执行 RAG 预检索，再将检索结果注入问题上下文
                String enrichedQuestion = chatService.buildAgentUserPrompt(
                        history,
                        request.getQuestion(),
                        chatService.shouldEnableRag(intentResult.getIntent()));
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                AtomicBoolean contentStarted = new AtomicBoolean(false);
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(enrichedQuestion);
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        contentStarted.set(true);
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            if (!contentStarted.get() && chatService.isToolExecutionFailure(error)) {
                                logger.warn("流式工具调用失败，降级为无工具直接回答: {}", chatService.rootCauseMessage(error));
                                String fallbackAnswer = chatService.answerWithoutTools(monitoredChatModel, systemPrompt, enrichedQuestion);
                                fullAnswerBuilder.append(fallbackAnswer);
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.content(fallbackAnswer), MediaType.APPLICATION_JSON));
                                tokenUsageRecorder.completeConversationSuccess(fallbackAnswer.length());
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                                emitter.complete();
                                return;
                            }

                            tokenUsageRecorder.completeConversationError(error.getMessage());
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史
                            session.addMessage(request.getQuestion(), fullAnswer, chatService);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                                request.getId(), session.getMessagePairCount());
                            tokenUsageRecorder.completeConversationSuccess(fullAnswer.length());
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                tokenUsageRecorder.completeConversationError(e.getMessage());
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = DashScopeChatModel.builder()
                        .dashScopeApi(dashScopeApi)
                        .defaultOptions(DashScopeChatOptions.builder()
                                .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                                .withTemperature(0.3)
                                .withMaxToken(8000)
                                .withTopP(0.9)
                                .build())
                        .build();

                ToolCallback[] toolCallbacks = chatService.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));
                
                // 调用 AiOpsService 执行分析流程
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 发送完整的告警分析报告
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));
                    
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                    
                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionInfo session = sessions.get(sessionId);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.createTime);
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private void recordIntent(String sessionId, IntentResult intentResult) {
        logger.info("意图识别完成: sessionId={}, intent={}, confidence={}, method={}, reason={}",
                sessionId,
                intentResult.getIntent(),
                String.format("%.3f", intentResult.getConfidence()),
                intentResult.getMethod(),
                intentResult.getReason());
        intentTracker.record(sessionId, intentResult);
        tokenUsageRecorder.recordIntent(
                sessionId,
                intentResult.getIntent().name(),
                intentResult.getConfidence(),
                intentResult.getMethod(),
                intentResult.getReason());
    }

    private String handleSystemOperation(SessionInfo session) {
        session.clearHistory();
        intentTracker.clear(session.getSessionId());
        return "会话历史已清空。";
    }

    private String executeAiOpsReport() throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.3)
                        .withMaxToken(8000)
                        .withTopP(0.9)
                        .build())
                .build();

        Optional<OverAllState> state = aiOpsService.executeAiOpsAnalysis(chatModel, chatService.getToolCallbacks());
        if (state.isEmpty()) {
            return "AIOps 多 Agent 编排未获取到有效结果。";
        }
        return aiOpsService.extractFinalReport(state.get())
                .orElse("AIOps 多 Agent 流程已完成，但未能生成最终报告。");
    }

    private void streamAiOpsReport(SseEmitter emitter) throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.3)
                        .withMaxToken(8000)
                        .withTopP(0.9)
                        .build())
                .build();

        emitter.send(SseEmitter.event().name("message")
                .data(SseMessage.content("正在读取告警并拆解任务...\n"), MediaType.APPLICATION_JSON));

        Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, chatService.getToolCallbacks());
        if (overAllStateOptional.isEmpty()) {
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
            return;
        }

        Optional<String> finalReportOptional = aiOpsService.extractFinalReport(overAllStateOptional.get());
        if (finalReportOptional.isPresent()) {
            String finalReportText = finalReportOptional.get();
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.content("**告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

            int chunkSize = 50;
            for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                int end = Math.min(i + chunkSize, finalReportText.length());
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content(finalReportText.substring(i, end)), MediaType.APPLICATION_JSON));
            }
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
        } else {
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.content("多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
        }

        emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
    }

    private SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(sessionId, id -> new SessionInfo(id));
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息
     * 管理单个会话的历史消息，支持自动清理和线程安全
     */
    private class SessionInfo {
        private final String sessionId;
        // 存储历史消息对：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        private final ReentrantLock lock;

        public SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        /**
         * 添加一对消息（用户问题 + AI回复）
         * 自动管理历史消息窗口大小
         */
        public void addMessage(String userQuestion, String aiAnswer, ChatService chatService) {
            lock.lock();
            try {
                // 添加用户消息
                messageHistory.add(buildMessage("user", userQuestion));

                // 添加AI回复
                messageHistory.add(buildMessage("assistant", aiAnswer));

                int maxMessages = MAX_WINDOW_SIZE * 2;
                if (messageHistory.size() >= maxMessages) {
                    List<Map<String, String>> snapshot = new ArrayList<>(messageHistory);
                    String summary = chatService.summarizeConversationMemory(snapshot);

                    messageHistory.clear();
                    messageHistory.add(buildMessage("user", "以下是此前 10 轮对话压缩后的历史摘要，请在后续回答中延续这些上下文。"));
                    messageHistory.add(buildMessage("assistant", summary));

                    logger.info("会话 {} 的短期记忆达到上限，已压缩为摘要记忆", sessionId);
                }

                logger.debug("会话 {} 更新历史消息，当前消息对数: {}", 
                    sessionId, messageHistory.size() / 2);

            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取历史消息（线程安全）
         * 返回副本以避免并发修改
         */
        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 清空历史消息
         */
        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取当前消息对数
         */
        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }

        public String getSessionId() {
            return sessionId;
        }

        private Map<String, String> buildMessage(String role, String content) {
            Map<String, String> message = new HashMap<>();
            message.put("role", role);
            message.put("content", content);
            return message;
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
