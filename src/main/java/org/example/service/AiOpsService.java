package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.example.observability.AgentTelemetry;
import org.example.observability.AgentObservabilityProperties;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的智能客服流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    @Autowired
    private AgentTelemetry telemetry;

    @Autowired
    private AgentObservabilityProperties observabilityProperties;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    private final ExecutorService workflowExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-workflow");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 执行智能客服分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks,
                "请主动分析当前可用信息，执行规划、查询与复核，并输出友好、专业的解答。",
                List.of());
    }

    /**
     * 执行带用户问题的规划-执行流程。
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @param userQuestion   用户原始问题
     * @param history        最近对话历史
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel,
                                                       ToolCallback[] toolCallbacks,
                                                       String userQuestion,
                                                       List<Map<String, String>> history) throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");
        AgentTelemetry.ObservedSpan workflow = telemetry.start("agent.workflow");
        try (Tracer.SpanInScope ignored = workflow.scope()) {
            workflow.tag("agent.workflow", "ai_ops");

            // Planner、Executor 和 Supervisor 均作为 workflow 的子 Span。
            ReactAgent plannerAgent = telemetry.observe("agent.planner", () -> buildPlannerAgent(chatModel, toolCallbacks));
            ReactAgent executorAgent = telemetry.observe("agent.executor", () -> buildExecutorAgent(chatModel, toolCallbacks));

        // 构建 Supervisor Agent
            SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

            String taskPrompt = buildTaskPrompt(userQuestion, history);

            logger.info("调用 Supervisor Agent 开始编排...");
            var parent = tracer.currentSpan();
            Future<Optional<OverAllState>> execution = workflowExecutor.submit(() -> {
                try (Tracer.SpanInScope ignoredScope = tracer.withSpan(parent)) {
                    return telemetry.observe("agent.supervisor", () -> supervisorAgent.invoke(taskPrompt));
                }
            });
            Optional<OverAllState> result;
            try {
                result = execution.get(observabilityProperties.getAgent().getMaxDuration().toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                execution.cancel(true);
                throw new RuntimeException("Agent workflow timeout", timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                execution.cancel(true);
                throw new RuntimeException("Agent workflow interrupted", interrupted);
            } catch (ExecutionException executionError) {
                Throwable cause = executionError.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(cause);
            }
            workflow.success();
            return result;
        } catch (RuntimeException error) {
            workflow.error(error);
            throw error;
        } finally {
            workflow.close();
        }
    }

    private String buildTaskPrompt(String userQuestion, List<Map<String, String>> history) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请围绕用户的真实问题执行规划、查询、复核和最终回答。")
                .append("必须基于工具返回的真实信息，禁止编造数据；如果连续多次查询失败，需要诚实说明无法找到相关信息。\n\n");
        prompt.append("用户问题:\n").append(userQuestion == null ? "" : userQuestion).append("\n\n");
        if (history != null && !history.isEmpty()) {
            prompt.append("最近对话历史:\n");
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                Map<String, String> message = history.get(i);
                prompt.append(message.getOrDefault("role", "unknown"))
                        .append(": ")
                        .append(message.getOrDefault("content", ""))
                        .append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("请按照 Planner -> Executor -> Replanner 的闭环执行，最终输出可直接给用户阅读的中文回复。");
        return prompt.toString();
    }

    @PreDestroy
    public void shutdownWorkflowExecutor() {
        workflowExecutor.shutdownNow();
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 提取 Planner 最终输出（包含完整的告警分析报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            logger.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责分析用户问题、规划与再规划解答步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .hooks(buildAgentHooks())
                .observationRegistry(observationRegistry)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .hooks(buildAgentHooks())
                .observationRegistry(observationRegistry)
                .outputKey("executor_feedback")
                .build();
    }

    private com.alibaba.cloud.ai.graph.agent.hook.Hook[] buildAgentHooks() {
        return new com.alibaba.cloud.ai.graph.agent.hook.Hook[]{
                com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook.builder()
                        .runLimit(observabilityProperties.getAgent().getMaxModelCalls())
                        .exitBehavior(com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook.ExitBehavior.ERROR)
                        .build(),
                com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook.builder()
                        .runLimit(observabilityProperties.getAgent().getMaxToolCalls())
                        .exitBehavior(com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook.ExitBehavior.ERROR)
                        .build()
        };
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词（智能客服场景）
     */
    private String buildPlannerPrompt() {
        return """
                你是智能客服 Planner，负责分析用户问题并制定解决方案：
                1. 读取用户问题 {input} 以及执行反馈 {executor_feedback}。
                2. 分析用户需求，确定是否需要查询产品文档、知识库或其他信息。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告中说明。
                
                ## 最终回复输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出友好、专业的客户服务回复**
                3. **回复必须遵循以下要求**：
                
                - 使用友好的语气，像专业客服一样与用户沟通
                - 基于查询到的信息，提供准确的解答
                - 如果没有找到相关信息，诚实地告诉用户并提供帮助
                - 使用清晰的格式，方便用户阅读
                
                ```
                # 问题解答
                
                ## 📝 用户问题
                [用户的原始问题]
                
                ## 📋 解答内容
                
                [基于知识库或工具查询结果的详细解答]
                
                ## 💡 建议
                
                [如果有相关建议，可以提供]
                
                ## 📞 需要进一步帮助？
                
                如果您还有其他问题，随时告诉我！
                ```
                
                **重要提醒**：
                - 最终输出必须是纯文本回复，不要包含 JSON 结构
                - 直接输出友好的回复内容
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果查询失败，如实说明，不要编造答案
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词（智能客服场景）
     */
    private String buildExecutorPrompt() {
        return """
                你是智能客服执行助手，负责读取 Planner 的最新指令 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将查询到的文档、常见问题解答等证据整理成结构化摘要，方便 Planner 生成最终回复。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。

                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "查询到相关产品文档，包含使用指南",
                  "evidence": "...",
                  "nextHint": "建议基于文档内容为用户提供详细解答"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词（智能客服场景）
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是智能客服主管，负责调度 planner_agent 与 executor_agent：
                1. 当需要分析用户问题或制定解答策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步查询。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出友好、专业的客户服务回复。
                5. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出友好的回复，告知用户当前无法找到相关信息，并表示愿意提供其他帮助。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }
}
