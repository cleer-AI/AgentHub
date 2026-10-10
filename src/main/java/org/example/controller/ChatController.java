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
import org.example.agent.router.AgentDispatchService;
import org.example.agent.router.AgentRoute;
import org.example.agent.router.AgentRouterService;
import org.example.agent.router.RouteDecision;
import org.example.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import io.micrometer.tracing.Tracer;
import org.example.observability.AgentTelemetry;
import org.example.observability.AgentRuntimeGuard;
import org.example.observability.AgentObservabilityProperties;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private AgentRouterService agentRouterService;

    @Autowired
    private AgentDispatchService agentDispatchService;

    @Autowired
    private RagService ragService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private SessionPersistenceService sessionPersistenceService;

    @Autowired
    private AgentTelemetry telemetry;

    @Autowired
    private AgentRuntimeGuard runtimeGuard;

    @Autowired
    private AgentObservabilityProperties observabilityProperties;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    
    // 最大历史消息窗口大小（成对计算：用户消息+AI回复=1对）
    private static final int MAX_WINDOW_SIZE = 6;

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        AgentTelemetry.ObservedSpan trace = telemetry.start("agent.chat");
        String traceId = trace.traceId();
        runtimeGuard.start(traceId);
        try (Tracer.SpanInScope ignored = trace.scope()) {
            Long userId = currentUserId();
            String sessionId = normalizeSessionId(request.getId());
            request.setId(sessionId);
            String requestId = normalizeRequestId(request.getRequestId());
            request.setRequestId(requestId);
            trace.tag("agent.request_id", telemetry.hash(requestId))
                    .tag("agent.session_id_hash", telemetry.hash(sessionId))
                    .tag("enduser.id_hash", telemetry.hash(String.valueOf(userId)))
                    .tag("agent.mode", "chat");
            logger.info("收到对话请求 - sessionHash={}, questionLength={}",
                    telemetry.hash(sessionId), request.getQuestion() == null ? 0 : request.getQuestion().length());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            // 获取或创建会话
            SessionInfo session = telemetry.observe("session.load", () -> getOrCreateSession(userId, sessionId));

            String savedAnswer = telemetry.observe("idempotency.lookup",
                    () -> sessionPersistenceService.findSavedAnswer(userId, sessionId, requestId));
            if (savedAnswer != null) {
                logger.info("返回幂等缓存结果 - sessionHash={}, requestHash={}",
                        telemetry.hash(sessionId), telemetry.hash(requestId));
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(savedAnswer)));
            }
            
            // 获取历史消息
            List<Map<String, String>> history = session.getHistory();
            logger.info("会话历史消息对数: {}", history.size() / 2);

            RouteDecision routeDecision = telemetry.observe("agent.router",
                    () -> agentRouterService.route(request.getQuestion(), history));
            trace.tag("agent.route", routeDecision.getRoute().name())
                    .tag("agent.task_type", routeDecision.getTaskType())
                    .tag("agent.complexity", routeDecision.getComplexity().name());
            logger.info("自动路由完成 - route={}, taskType={}, complexity={}, confidence={}, reason={}",
                    routeDecision.getRoute(), routeDecision.getTaskType(), routeDecision.getComplexity(),
                    routeDecision.getConfidence(), routeDecision.getReason());

            String fullAnswer = telemetry.observe("agent.dispatch",
                    () -> agentDispatchService.dispatch(routeDecision, request.getQuestion(), history));
            
            // 更新会话历史
            // 持久化到数据库
            try {
                if (telemetry.observe("session.persist", () -> sessionPersistenceService.saveMessagePair(
                        userId, sessionId, requestId, request.getQuestion(), fullAnswer))) {
                    session.addMessage(request.getQuestion(), fullAnswer);
                }
            } catch (Exception e) {
                logger.error("持久化会话失败", e);
            }
            
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                telemetry.hash(request.getId()), session.getMessagePairCount());
            
            trace.success();
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer, routeDecision)));

        } catch (Exception e) {
            trace.error(e);
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        } finally {
            runtimeGuard.finish(traceId);
            trace.close();
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            Long userId = currentUserId();
            logger.info("收到清空会话历史请求 - sessionHash={}", telemetry.hash(request.getId()));

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            String cacheKey = cacheKey(userId, request.getId());
            SessionInfo session = sessions.get(cacheKey);
            if (session != null) {
                session.clearHistory();
            }
            if (session != null || sessionPersistenceService.findSession(userId, request.getId()) != null) {
                sessionPersistenceService.clearSession(userId, request.getId());
                sessions.remove(cacheKey);
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
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询内部文档等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(observabilityProperties.getSse().getTimeout().toMillis());
        Long userId;
        try {
            userId = currentUserId();
            request.setId(normalizeSessionId(request.getId()));
            request.setRequestId(normalizeRequestId(request.getRequestId()));
        } catch (ResponseStatusException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        final Long authenticatedUserId = userId;
        final String sessionId = request.getId();
        final String requestId = request.getRequestId();
        final AgentTelemetry.ObservedSpan streamTrace = telemetry.start("agent.chat_stream");
        final String traceId = streamTrace.traceId();
        final AtomicBoolean traceFinished = new AtomicBoolean();
        final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        runtimeGuard.start(traceId);
        streamTrace.tag("agent.request_id", telemetry.hash(requestId))
                .tag("agent.session_id_hash", telemetry.hash(sessionId))
                .tag("enduser.id_hash", telemetry.hash(String.valueOf(authenticatedUserId)))
                .tag("agent.mode", "stream");
        Runnable finishTrace = () -> {
            if (traceFinished.compareAndSet(false, true)) {
                runtimeGuard.finish(traceId);
                streamTrace.close();
            }
        };
        emitter.onTimeout(() -> {
            streamTrace.event("sse.timeout");
            Disposable subscription = subscriptionRef.get();
            if (subscription != null) subscription.dispose();
            finishTrace.run();
        });
        emitter.onCompletion(finishTrace);
        emitter.onError(error -> {
            streamTrace.error(error);
            finishTrace.run();
        });

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
            try (Tracer.SpanInScope ignored = streamTrace.scope()) {
                logger.info("收到 ReactAgent 流式请求 - sessionHash={}, questionLength={}",
                        telemetry.hash(sessionId), request.getQuestion().length());

                // 获取或创建会话
                SessionInfo session = telemetry.observe("session.load",
                        () -> getOrCreateSession(authenticatedUserId, sessionId));

                String savedAnswer = telemetry.observe("idempotency.lookup", () ->
                        sessionPersistenceService.findSavedAnswer(authenticatedUserId, sessionId, requestId));
                if (savedAnswer != null) {
                    logger.info("返回幂等缓存结果 - sessionHash={}, requestHash={}",
                            telemetry.hash(sessionId), telemetry.hash(requestId));
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content(savedAnswer), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }
                
                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                RouteDecision routeDecision = telemetry.observe("agent.router",
                        () -> agentRouterService.route(request.getQuestion(), history));
                streamTrace.tag("agent.route", routeDecision.getRoute().name())
                        .tag("agent.task_type", routeDecision.getTaskType())
                        .tag("agent.complexity", routeDecision.getComplexity().name());
                logger.info("流式自动路由完成 - route={}, taskType={}, complexity={}, confidence={}, reason={}",
                        routeDecision.getRoute(), routeDecision.getTaskType(), routeDecision.getComplexity(),
                        routeDecision.getConfidence(), routeDecision.getReason());

                if (routeDecision.getRoute() != AgentRoute.REACT) {
                    String answer = telemetry.observe("agent.dispatch",
                            () -> agentDispatchService.dispatch(routeDecision, request.getQuestion(), history));
                    int chunkSize = 80;
                    for (int i = 0; i < answer.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, answer.length());
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(SseMessage.content(answer.substring(i, end)), MediaType.APPLICATION_JSON));
                    }
                    try {
                        if (telemetry.observe("session.persist", () -> sessionPersistenceService.saveMessagePair(
                                authenticatedUserId, sessionId, requestId, request.getQuestion(), answer))) {
                            session.addMessage(request.getQuestion(), answer);
                        }
                    } catch (Exception e) {
                        logger.error("持久化流式会话失败", e);
                    }
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                    streamTrace.event("agent.stream_complete");
                    streamTrace.success();
                    emitter.complete();
                    return;
                }

                // ==================== RAG 检索逻辑 ====================
                // 先从 Milvus 检索与问题相关的文档片段
                int topK = 3; // 检索最相关的3个片段
                String userQuestion = request.getQuestion();
                String contextPrompt = "";
                
                try {
                    List<VectorSearchService.SearchResult> searchResults = 
                        vectorSearchService.searchSimilarDocuments(userQuestion, topK);
                    
                    if (searchResults != null && !searchResults.isEmpty()) {
                        logger.info("RAG 检索到 {} 个相关文档片段", searchResults.size());
                        
                        // 构建上下文
                        StringBuilder contextBuilder = new StringBuilder();
                        contextBuilder.append("【参考资料】\n\n");
                        for (int i = 0; i < searchResults.size(); i++) {
                            VectorSearchService.SearchResult result = searchResults.get(i);
                            contextBuilder.append("【参考 ").append(i + 1).append("】\n");
                            contextBuilder.append(result.getContent()).append("\n\n");
                        }
                        contextPrompt = contextBuilder.toString();
                        
                        
                    } else {
                        logger.info("RAG 未检索到相关文档");
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(SseMessage.content("🔍 未在知识库中找到直接相关的内容，将基于通用知识回答\n\n")));
                    }
                } catch (Exception ragError) {
                    logger.warn("RAG 检索失败，将基于通用知识回答: {}", ragError.getMessage());
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.content("⚠️ 知识库检索失败，将基于通用知识回答\n\n")));
                }
                // ==================== RAG 检索结束 ====================
                
                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

                // 记录可用工具
                chatService.logAvailableTools();

                boolean opsMode = shouldUseOpsMode(routeDecision);
                logger.info("对话模式 - OpsMode: {}", opsMode);
                
                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息和 RAG 上下文）
                String systemPrompt = chatService.buildSystemPrompt(history, opsMode);
                
                // 如果有 RAG 上下文，将其加入到用户问题中
                String finalQuestion = userQuestion;
                if (!contextPrompt.isEmpty()) {
                    finalQuestion = "请根据以下参考资料回答问题。\n\n" + contextPrompt + "\n\n用户问题：" + userQuestion;
                    logger.info("已添加 RAG 上下文到问题中，上下文字符数: {}", contextPrompt.length());
                }
                
                // 创建 ReactAgent（支持模式切换）
                ReactAgent agent = ReactAgent.builder()
                        .name(opsMode ? "ai_ops_agent" : "intelligent_assistant")
                        .model(chatModel)
                        .systemPrompt(systemPrompt)
                        .methodTools(chatService.buildMethodToolsArray(opsMode))
                        .tools(chatService.getToolCallbacks())
                        .hooks(chatService.buildAgentHooks())
                        .observationRegistry(chatService.getObservationRegistry())
                        .build();
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                AtomicBoolean firstToken = new AtomicBoolean();
                long streamStarted = System.nanoTime();
                
                // 使用 agent.stream() 进行流式对话（使用带 RAG 上下文的最终问题）
                Flux<NodeOutput> stream = agent.stream(finalQuestion)
                        .timeout(observabilityProperties.getAgent().getMaxDuration());
                
                Disposable subscription = stream.subscribe(
                    output -> {
                        try (Tracer.SpanInScope callbackScope = streamTrace.scope()) {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        boolean firstChunk = firstToken.compareAndSet(false, true);
                                        if (firstChunk) {
                                            long firstTokenNanos = System.nanoTime() - streamStarted;
                                            streamTrace.event("model.first_token");
                                            telemetry.record("agent.sse.first.token", firstTokenNanos,
                                                    "mode", opsMode ? "ops" : "chat");
                                        }
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        long sendStarted = System.nanoTime();
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        telemetry.record("agent.sse.send.duration",
                                                System.nanoTime() - sendStarted, "status", "success");
                                        if (firstChunk) streamTrace.event("sse.first_token_sent");
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    streamTrace.event("model.last_token");
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
                        try (Tracer.SpanInScope callbackScope = streamTrace.scope()) {
                            // 错误只通知当前 SSE 客户端，不重新播放已发送的数据。
                            logger.error("ReactAgent 流式对话失败", error);
                            streamTrace.error(error);
                            streamTrace.event("agent.stream_error");
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                            } catch (IOException ex) {
                                logger.error("发送错误消息失败", ex);
                            }
                            emitter.completeWithError(error);
                        }
                    },
                    () -> {
                        // 完成处理
                        try (Tracer.SpanInScope callbackScope = streamTrace.scope()) {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - sessionHash={}, answerLength={}",
                                telemetry.hash(request.getId()), fullAnswer.length());
                            
                            // 持久化到数据库
                            try {
                                if (telemetry.observe("session.persist", () -> sessionPersistenceService.saveMessagePair(
                                        authenticatedUserId, sessionId, requestId, userQuestion, fullAnswer))) {
                                    session.addMessage(userQuestion, fullAnswer);
                                }
                            } catch (Exception e) {
                                logger.error("持久化流式会话失败", e);
                            }
                            
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                                telemetry.hash(request.getId()), session.getMessagePairCount());
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            streamTrace.event("agent.stream_complete");
                            streamTrace.success();
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );
                subscriptionRef.set(subscription);

            } catch (Exception e) {
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
     * AI 智能客服接口（SSE 流式模式）- 多 Agent 协作解答用户问题
     * 无需用户输入，自动执行客服分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能客服请求 - 启动多 Agent 协作流程");

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

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在分析问题并制定解答方案...\n")));
                
                // 调用 AiOpsService 执行分析流程
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("智能客服编排完成，开始提取最终解答...");

                // 提取最终解答
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终解答
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到最终解答，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 发送完整的解答
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **问题解答**\n\n"), MediaType.APPLICATION_JSON));
                    
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
                    
                    logger.info("最终解答已完整输出");
                } else {
                    logger.warn("未能提取到最终解答");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成解答。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("智能客服多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("智能客服多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("智能客服流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
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
            Long userId = currentUserId();
            logger.info("收到获取会话信息请求 - sessionHash={}", telemetry.hash(sessionId));

            SessionInfo session = sessions.get(cacheKey(userId, sessionId));
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.createTime);
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                if (sessionPersistenceService.findSession(userId, sessionId) == null) {
                    return ResponseEntity.ok(ApiResponse.error("会话不存在"));
                }
                return ResponseEntity.ok(ApiResponse.success(new SessionInfoResponse(sessionId, 0, 0)));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private SessionInfo getOrCreateSession(Long userId, String sessionId) {
        String key = cacheKey(userId, sessionId);
        return sessions.computeIfAbsent(key, id -> {
            List<Map<String, String>> history = sessionPersistenceService.getHistoryMessages(userId, sessionId);
            SessionInfo session = new SessionInfo(sessionId);
            session.addHistoryMessages(history);
            sessionPersistenceService.getOrCreateSession(userId, sessionId);
            return session;
        });
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
    }

    private String normalizeRequestId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private String cacheKey(Long userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return (Long) authentication.getPrincipal();
    }

    private boolean shouldUseOpsMode(RouteDecision decision) {
        if (decision == null) return false;
        String taskType = decision.getTaskType() == null ? "" : decision.getTaskType();
        if (taskType.contains("ai_ops") || taskType.contains("multi_step")) return true;
        List<String> tools = decision.getRequiredTools();
        return tools != null && tools.stream().anyMatch(tool ->
                "queryPrometheusAlerts".equals(tool)
                        || "queryLogs".equals(tool)
                        || "getAvailableLogTopics".equals(tool));
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息
     * 管理单个会话的历史消息，支持自动清理和线程安全
     */
    private static class SessionInfo {
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
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                // 添加用户消息
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                // 添加AI回复
                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                // 自动清理：保持最多 MAX_WINDOW_SIZE 对消息
                // 每对消息包含2条记录（user + assistant）
                int maxMessages = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > maxMessages) {
                    // 成对删除最旧的消息（删除前2条）
                    messageHistory.remove(0); // 删除最旧的用户消息
                    if (!messageHistory.isEmpty()) {
                        messageHistory.remove(0); // 删除对应的AI回复
                    }
                }

                logger.debug("会话历史已更新，当前消息对数: {}", messageHistory.size() / 2);

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
         * 批量添加历史消息（用于从数据库加载）
         */
        public void addHistoryMessages(List<Map<String, String>> messages) {
            lock.lock();
            try {
                messageHistory.addAll(messages);
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
                logger.info("会话历史消息已清空");
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

        @com.fasterxml.jackson.annotation.JsonProperty(value = "RequestId")
        @com.fasterxml.jackson.annotation.JsonAlias({"requestId", "request_id"})
        private String RequestId;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "OpsMode")
        @com.fasterxml.jackson.annotation.JsonAlias({"opsMode", "ops_mode"})
        private Boolean OpsMode = false;

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

        public SessionInfoResponse() {}

        public SessionInfoResponse(String sessionId, int messagePairCount, long createTime) {
            this.sessionId = sessionId;
            this.messagePairCount = messagePairCount;
            this.createTime = createTime;
        }
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
        private String route;
        private String taskType;
        private String complexity;
        private Double routeConfidence;
        private String routeReason;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse success(String answer, RouteDecision routeDecision) {
            ChatResponse response = success(answer);
            if (routeDecision != null) {
                response.setRoute(routeDecision.getRoute() == null ? null : routeDecision.getRoute().name());
                response.setTaskType(routeDecision.getTaskType());
                response.setComplexity(routeDecision.getComplexity() == null ? null : routeDecision.getComplexity().name());
                response.setRouteConfidence(routeDecision.getConfidence());
                response.setRouteReason(routeDecision.getReason());
            }
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
