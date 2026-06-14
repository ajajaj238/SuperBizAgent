package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
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
import org.example.service.ModelRoutingService;
import org.example.config.UserContext;
import org.example.entity.SessionIndex;
import org.example.service.intent.HybridIntentClassifier;
import org.example.service.intent.IntentResult;
import org.example.service.intent.SessionIntentTracker;
import org.example.service.intent.ToolFilter;
import org.example.service.intent.UserIntent;
import org.example.service.session.PersistentSessionService;
import org.example.service.session.SessionContext;
import org.example.service.session.PersonaService;
import org.example.service.session.ChatMessage;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Autowired
    private PersistentSessionService persistentSessionService;

    @Autowired
    private PersonaService personaService;

    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 20, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            new ThreadPoolExecutor.CallerRunsPolicy());

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
            SessionContext session = getOrCreateSession(request.getId());
            tokenUsageRecorder.beginConversation(session.sessionId(), "/api/chat", request.getQuestion());
            
            // 获取历史消息
            List<Map<String, String>> history = persistentSessionService.getRecentHistory(session);
            List<String> semanticMemories = persistentSessionService.getSemanticMemories(session, request.getQuestion());
            logger.info("会话历史消息对数: {}", history.size() / 2);

            IntentResult intentResult = intentClassifier.classify(request.getQuestion(), session.sessionId(), history);
            recordIntent(session.sessionId(), intentResult);

            if (intentResult.getIntent() == UserIntent.SYSTEM_OPERATION) {
                String answer = handleSystemOperation(session);
                tokenUsageRecorder.completeConversationSuccess(answer.length());
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(answer)));
            }

            if (intentResult.getIntent() == UserIntent.ALERT_DIAGNOSIS) {
                String report = executeAiOpsReport();
                int pairCount = persistentSessionService.appendConversation(session, request.getQuestion(), report, chatService);
                tokenUsageRecorder.completeConversationSuccess(report.length());
                logger.info("已更新持久化会话历史 - SessionId: {}, 当前消息对数: {}",
                        session.sessionId(), pairCount);
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(report)));
            }

            // 创建 DashScope API 和 ChatModel
            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            ModelRoutingService.ModelSpec modelSpec = chatService.modelSpecForIntent(intentResult.getIntent());
            DashScopeChatModel chatModel = chatService.createChatModel(dashScopeApi, modelSpec);

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            // 构建系统提示词（包含历史消息）
            String systemPrompt = intentResult.getIntent() == UserIntent.AMBIGUOUS
                    ? chatService.buildSystemPrompt(history)
                    : chatService.buildIntentSystemPrompt(intentResult.getIntent());
            var monitoredChatModel = chatService.createMonitoredChatModel(chatModel, modelSpec);
            
            // 创建 ReactAgent
            ToolFilter toolFilter = chatService.toolFilterForIntent(intentResult.getIntent());
            ReactAgent agent = chatService.createReactAgent(monitoredChatModel, systemPrompt, toolFilter);

            // 注入用户画像
            String personaPrompt = personaService.buildPersonaPrompt(session.userId());

            // 每次回答前先执行 RAG 预检索，再将检索结果注入问题上下文
            String enrichedQuestion = chatService.buildAgentUserPrompt(
                    history,
                    semanticMemories,
                    request.getQuestion(),
                    chatService.shouldEnableRag(intentResult.getIntent()));

            // 将用户画像追加到问题前
            if (!personaPrompt.isBlank()) {
                enrichedQuestion = personaPrompt + "\n" + enrichedQuestion;
            }
            
            // 执行对话
            String fullAnswer = chatService.executeChat(agent, monitoredChatModel, systemPrompt, enrichedQuestion);
            
            // 更新会话历史
            int pairCount = persistentSessionService.appendConversation(session, request.getQuestion(), fullAnswer, chatService);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                session.sessionId(), pairCount);
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

            Optional<SessionContext> session = persistentSessionService.findSession(request.getId());
            if (session.isPresent()) {
                persistentSessionService.clearSession(session.get());
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
        UserContext.UserInfo userInfo = UserContext.current();

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
                UserContext.set(userInfo);
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话
                SessionContext session = getOrCreateSession(request.getId());
                tokenUsageRecorder.beginConversation(session.sessionId(), "/api/chat_stream", request.getQuestion());
                
                // 获取历史消息
                List<Map<String, String>> history = persistentSessionService.getRecentHistory(session);
                List<String> semanticMemories = persistentSessionService.getSemanticMemories(session, request.getQuestion());
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                IntentResult intentResult = intentClassifier.classify(request.getQuestion(), session.sessionId(), history);
                recordIntent(session.sessionId(), intentResult);

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
                    persistentSessionService.appendConversation(session, request.getQuestion(), answer, chatService);
                    tokenUsageRecorder.completeConversationSuccess(answer.length());
                    emitter.complete();
                    return;
                }

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                ModelRoutingService.ModelSpec modelSpec = chatService.modelSpecForIntent(intentResult.getIntent());
                DashScopeChatModel chatModel = chatService.createChatModel(dashScopeApi, modelSpec);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = intentResult.getIntent() == UserIntent.AMBIGUOUS
                        ? chatService.buildSystemPrompt(history)
                        : chatService.buildIntentSystemPrompt(intentResult.getIntent());
                var monitoredChatModel = chatService.createMonitoredChatModel(chatModel, modelSpec);
                
                // 创建 ReactAgent
                ToolFilter toolFilter = chatService.toolFilterForIntent(intentResult.getIntent());
                ReactAgent agent = chatService.createReactAgent(monitoredChatModel, systemPrompt, toolFilter);

                // 注入用户画像
                String personaPrompt = personaService.buildPersonaPrompt(session.userId());

                // 每次回答前先执行 RAG 预检索，再将检索结果注入问题上下文
                String enrichedQuestion = chatService.buildAgentUserPrompt(
                        history,
                        semanticMemories,
                        request.getQuestion(),
                        chatService.shouldEnableRag(intentResult.getIntent()));

                // 将用户画像追加到问题前
                String finalQuestion = !personaPrompt.isBlank()
                        ? personaPrompt + "\n" + enrichedQuestion
                        : enrichedQuestion;

                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                AtomicBoolean contentStarted = new AtomicBoolean(false);
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(finalQuestion);
                
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
                                String fallbackAnswer = chatService.answerWithoutTools(monitoredChatModel, systemPrompt, finalQuestion);
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
                            int pairCount = persistentSessionService.appendConversation(session, request.getQuestion(), fullAnswer, chatService);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                                session.sessionId(), pairCount);
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
            } finally {
                UserContext.clear();
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
        UserContext.UserInfo userInfo = UserContext.current();

        executor.execute(() -> {
            try {
                UserContext.set(userInfo);
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createChatModelForTask(
                        dashScopeApi, ModelRoutingService.ModelTask.AIOPS_REPORT);

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
            } finally {
                UserContext.clear();
            }
        });

        return emitter;
    }


    /**
     * 退出前压缩当前用户的所有会话摘要到 Milvus
     */
    @PostMapping("/session/compress")
    public ResponseEntity<ApiResponse<String>> compressSessions() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.ok(ApiResponse.success("ok"));
        }
        // 异步执行摘要压缩，不阻塞前端退出
        executor.execute(() -> persistentSessionService.compressUserSessions(userId));
        return ResponseEntity.ok(ApiResponse.success("ok"));
    }

    /**
     * 分页加载会话消息
     */
    @GetMapping("/chat/session/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessage>>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int afterIndex,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<ChatMessage> messages = persistentSessionService.loadMessagesPage(sessionId, afterIndex, limit);
            return ResponseEntity.ok(ApiResponse.success(messages));
        } catch (Exception e) {
            logger.error("加载会话消息失败: sessionId={}", sessionId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            Optional<SessionContext> session = persistentSessionService.findSession(sessionId);
            if (session.isPresent()) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.get().messagePairCount());
                response.setCreateTime(session.get().createTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取当前用户的会话列表（按创建时间倒序）
     */
    @GetMapping("/chat/sessions")
    public ResponseEntity<ApiResponse<List<SessionInfoResponse>>> getUserSessions() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<SessionIndex> sessions = persistentSessionService.listUserSessions(userId);
        List<SessionInfoResponse> result = sessions.stream().map(s -> {
            SessionInfoResponse r = new SessionInfoResponse();
            r.setSessionId(s.getSessionId());
            r.setTitle(s.getTitle() != null && !s.getTitle().isBlank() ? s.getTitle() : "新对话");
            r.setMessagePairCount(s.getMessageCount() != null ? s.getMessageCount() : 0);
            r.setCreateTime(s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            return r;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 删除指定会话
     */
    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<String>> deleteSession(@PathVariable String sessionId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.ok(ApiResponse.error("未登录"));
        }
        Optional<SessionContext> session = persistentSessionService.findSession(sessionId);
        if (session.isPresent()) {
            persistentSessionService.clearSession(session.get());
            logger.info("会话已删除: sessionId={}, userId={}", sessionId, userId);
            return ResponseEntity.ok(ApiResponse.success("ok"));
        }
        return ResponseEntity.ok(ApiResponse.error("会话不存在"));
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

    private String handleSystemOperation(SessionContext session) {
        persistentSessionService.clearSession(session);
        intentTracker.clear(session.sessionId());
        return "会话历史已清空。";
    }

    private String executeAiOpsReport() throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createChatModelForTask(
                dashScopeApi, ModelRoutingService.ModelTask.AIOPS_REPORT);

        Optional<OverAllState> state = aiOpsService.executeAiOpsAnalysis(chatModel, chatService.getToolCallbacks());
        if (state.isEmpty()) {
            return "AIOps 多 Agent 编排未获取到有效结果。";
        }
        return aiOpsService.extractFinalReport(state.get())
                .orElse("AIOps 多 Agent 流程已完成，但未能生成最终报告。");
    }

    private void streamAiOpsReport(SseEmitter emitter) throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createChatModelForTask(
                dashScopeApi, ModelRoutingService.ModelTask.AIOPS_REPORT);

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

    private SessionContext getOrCreateSession(String sessionId) {
        String username = UserContext.getUsername();
        if (username == null) {
            username = "default-user";
        }
        return persistentSessionService.getOrCreateSession(sessionId, username);
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
        private String title;
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
