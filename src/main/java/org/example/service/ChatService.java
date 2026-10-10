package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.OrderQueryTools;
import org.example.entity.SkillMetadata;
import org.example.service.SkillRegistry;
import org.example.tool.skill.ListSkillsTool;
import org.example.tool.skill.ReadSkillTool;
import org.example.tool.skill.ListSkillResourcesTool;
import org.example.tool.skill.ReadResourceTool;
import org.example.tool.skill.ExecuteScriptTool;
import org.example.tool.skill.CalculateTaxTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.example.observability.AgentTelemetry;
import io.micrometer.tracing.Tracer;
import io.micrometer.observation.ObservationRegistry;
import org.example.observability.AgentObservabilityProperties;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private OrderQueryTools orderQueryTools;

    @Autowired(required = false)
    private org.example.agent.tool.QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)
    private org.example.agent.tool.QueryLogsTools queryLogsTools;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    @Autowired
    private ListSkillsTool listSkillsTool;

    @Autowired
    private ReadSkillTool readSkillTool;

    @Autowired
    private ListSkillResourcesTool listSkillResourcesTool;

    @Autowired
    private ReadResourceTool readResourceTool;

    @Autowired
    private ExecuteScriptTool executeScriptTool;
    
    @Autowired
    private CalculateTaxTool calculateTaxTool;

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private RetryExecutor retryExecutor;

    @Autowired
    private AgentTelemetry telemetry;

    @Autowired
    private AgentObservabilityProperties observabilityProperties;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    private final ExecutorService modelExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-model-call");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // 基础系统提示 - 智能客服助手
        systemPromptBuilder.append("你是一个专业的智能客服助手，致力于为用户提供优质的客户服务支持。\n");
        systemPromptBuilder.append("你可以：\n");
        systemPromptBuilder.append("- 获取当前时间\n");
        systemPromptBuilder.append("- 查询订单基本信息（订单号、状态、金额等）\n");
        systemPromptBuilder.append("- 查询订单物流信息（物流状态、运输轨迹等）\n");
        systemPromptBuilder.append("- 查询公司产品文档、使用指南和常见问题解答\n");
        systemPromptBuilder.append("- 帮助用户解决产品使用中遇到的问题\n");
        systemPromptBuilder.append("- 提供友好、专业的客户服务\n");
        systemPromptBuilder.append("\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询订单基本信息时，使用 queryOrderInfo 工具。\n");
        systemPromptBuilder.append("当用户需要查询订单物流信息时，使用 queryOrderTracking 工具。\n");
        systemPromptBuilder.append("当用户需要查询产品文档、使用指南或常见问题时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户有其他问题时，请根据你的知识直接回答。\n\n");
        
        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("客服助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }
        
        systemPromptBuilder.append("请基于以上对话历史，友好、专业地回答用户的新问题。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 智能客服场景使用的工具
     */
    public Object[] buildMethodToolsArray() {
        return buildMethodToolsArray(false);
    }

    /**
     * 动态构建方法工具数组（支持模式切换）
     * @param opsMode 是否为运维模式
     */
    public Object[] buildMethodToolsArray(boolean opsMode) {
        if (opsMode) {
            // 运维模式：使用运维相关工具 + Skill 工具
            if (queryLogsTools != null) {
                return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools, listSkillsTool, readSkillTool, listSkillResourcesTool, readResourceTool, executeScriptTool, calculateTaxTool};
            } else {
                return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, listSkillsTool, readSkillTool, listSkillResourcesTool, readResourceTool, executeScriptTool, calculateTaxTool};
            }
        } else {
            // 客服模式：使用客服相关工具 + Skill 工具
            return new Object[]{dateTimeTools, internalDocsTools, orderQueryTools, listSkillsTool, readSkillTool, listSkillResourcesTool, readResourceTool, executeScriptTool, calculateTaxTool};
        }
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @param opsMode 是否为运维模式
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history, boolean opsMode) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // 获取所有可用的 skill（name 和 description）
        List<SkillMetadata> skills = skillRegistry.listAllSkillsMetadata();
        if (!skills.isEmpty()) {
            systemPromptBuilder.append("以下是可用的 Agent Skill（技能）：\n");
            for (SkillMetadata skill : skills) {
                systemPromptBuilder.append("- name: ").append(skill.getName()).append("\n");
                systemPromptBuilder.append("  description: ").append(skill.getDescription()).append("\n");
            }
            systemPromptBuilder.append("\n");
            systemPromptBuilder.append("如果用户的问题与某个 skill 匹配，请使用 read_skill 工具读取该 skill 的详细内容！\n\n");
        }

        if (opsMode) {
            // 运维模式提示词
            systemPromptBuilder.append("你是一个专业的 AI 运维助手，可以获取当前时间、查询天气信息、搜索内部文档知识库。\n");
            systemPromptBuilder.append("你还可以使用 Agent Skill 来处理特殊任务！\n\n");
            systemPromptBuilder.append("使用 Skill 工具的流程：\n");
            systemPromptBuilder.append("1. 如果用户的问题与上面列出的某个 skill 匹配，调用 read_skill 工具读取该 skill 的详细内容\n");
            systemPromptBuilder.append("2. 根据 skill 的指令，调用相应的工具或执行脚本\n");
            systemPromptBuilder.append("3. 如果需要调用 script，使用 execute_script 工具\n");
            systemPromptBuilder.append("4. 如果需要参考资源，先使用 list_skill_resources 列出资源，再用 read_resource 读取\n\n");
            systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
            systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        } else {
            // 客服模式提示词
            systemPromptBuilder.append("你是一个专业的智能客服助手，致力于为用户提供优质的客户服务支持。\n");
            systemPromptBuilder.append("你还可以使用 Agent Skill 来处理特殊任务！\n\n");
            systemPromptBuilder.append("你可以：\n");
            systemPromptBuilder.append("- 获取当前时间\n");
            systemPromptBuilder.append("- 查询订单基本信息（订单号、状态、金额等）\n");
            systemPromptBuilder.append("- 查询订单物流信息（物流状态、运输轨迹等）\n");
            systemPromptBuilder.append("- 查询公司产品文档、使用指南和常见问题解答\n");
            systemPromptBuilder.append("- 帮助用户解决产品使用中遇到的问题\n");
            systemPromptBuilder.append("- 提供友好、专业的客户服务\n");
            systemPromptBuilder.append("- 使用 Agent Skill 处理特殊任务\n");
            systemPromptBuilder.append("\n");
            systemPromptBuilder.append("使用 Skill 工具的流程：\n");
            systemPromptBuilder.append("1. 如果用户的问题与上面列出的某个 skill 匹配，调用 read_skill 工具读取该 skill 的详细内容\n");
            systemPromptBuilder.append("2. 根据 skill 的指令，调用相应的工具或执行脚本\n");
            systemPromptBuilder.append("3. 如果需要调用 script，使用 execute_script 工具\n");
            systemPromptBuilder.append("4. 如果需要参考资源，先使用 list_skill_resources 列出资源，再用 read_resource 读取\n\n");
            systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
            systemPromptBuilder.append("当用户需要查询订单基本信息时，使用 queryOrderInfo 工具。\n");
            systemPromptBuilder.append("当用户需要查询订单物流信息时，使用 queryOrderTracking 工具。\n");
            systemPromptBuilder.append("当用户需要查询产品文档、使用指南或常见问题时，使用 queryInternalDocs 工具。\n");
            systemPromptBuilder.append("当用户有其他问题时，先看看有没有合适的 skill，如果有合适的就使用 skill 处理，没有的话再根据你的知识直接回答。\n\n");
        }
        
        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append(opsMode ? "助手: " : "客服助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }
        
        systemPromptBuilder.append("请基于以上对话历史，").append(opsMode ? "" : "友好、专业地").append("回答用户的新问题。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        if (tools == null) {
            return new ToolCallback[0];
        }
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = getToolCallbacks();
        if (toolCallbacks.length == 0) {
            logger.info("MCP 未启用或未提供远程工具，继续使用本地方法工具");
            return;
        }
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return createReactAgent(chatModel, systemPrompt, false);
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @param opsMode 是否使用运维工具集
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt, boolean opsMode) {
        return ReactAgent.builder()
                .name(opsMode ? "ai_ops_agent" : "intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray(opsMode))
                .tools(getToolCallbacks())
                .hooks(buildAgentHooks())
                .observationRegistry(observationRegistry)
                .build();
    }

    public Hook[] buildAgentHooks() {
        ModelCallLimitHook modelLimit = ModelCallLimitHook.builder()
                .runLimit(observabilityProperties.getAgent().getMaxModelCalls())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();
        ToolCallLimitHook toolLimit = ToolCallLimitHook.builder()
                .runLimit(observabilityProperties.getAgent().getMaxToolCalls())
                .exitBehavior(ToolCallLimitHook.ExitBehavior.ERROR)
                .build();
        return new Hook[]{modelLimit, toolLimit};
    }

    public ObservationRegistry getObservationRegistry() {
        return observationRegistry;
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var parent = tracer.currentSpan();
        Future<String> execution = modelExecutor.submit(() -> {
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
                var response = telemetry.observe("llm.chat", () ->
                        retryExecutor.execute("dashscope-chat", () -> agent.call(question)));
                return response.getText();
            }
        });
        try {
            String answer = execution.get(observabilityProperties.getAgent().getMaxDuration().toMillis(),
                    TimeUnit.MILLISECONDS);
            logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
            return answer;
        } catch (TimeoutException timeout) {
            execution.cancel(true);
            throw new RuntimeException("Agent execution timeout", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            execution.cancel(true);
            throw new RuntimeException("Agent execution interrupted", interrupted);
        } catch (ExecutionException executionError) {
            Throwable cause = executionError.getCause();
            if (cause instanceof GraphRunnerException graphError) throw graphError;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(cause);
        }
    }

    @PreDestroy
    public void shutdownModelExecutor() {
        modelExecutor.shutdownNow();
    }
}
