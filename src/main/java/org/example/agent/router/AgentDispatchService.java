package org.example.agent.router;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.example.observability.AgentTelemetry;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(AgentDispatchService.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    private AgentTelemetry telemetry;

    public String dispatch(RouteDecision decision, String question, List<Map<String, String>> history) throws Exception {
        if (decision == null || decision.getRoute() == null) {
            return executeReact(question, history, false);
        }
        long started = System.nanoTime();
        try {
            String answer = switch (decision.getRoute()) {
                case CLARIFY -> decision.getClarifyQuestion();
                case PLAN_AND_EXECUTE -> executePlanAndExecute(question, history);
                case REACT -> executeReact(question, history, shouldUseOpsMode(decision));
            };
            telemetry.record("agent.route.execution.duration", System.nanoTime() - started,
                    "route", decision.getRoute().name());
            telemetry.increment("agent.route.execution.total", "status", "success");
            return answer;
        } catch (Exception error) {
            telemetry.increment("agent.route.execution.total", "status", "error");
            throw error;
        }
    }

    private String executeReact(String question, List<Map<String, String>> history, boolean opsMode) throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
        chatService.logAvailableTools();
        String systemPrompt = chatService.buildSystemPrompt(history, opsMode);
        ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt, opsMode);
        logger.info("AgentDispatch 使用 ReAct 执行, opsMode={}", opsMode);
        return chatService.executeChat(agent, question);
    }

    private String executePlanAndExecute(String question, List<Map<String, String>> history) throws Exception {
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
        logger.info("AgentDispatch 使用 Plan-and-Execute 执行");
        Optional<OverAllState> stateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks, question, history);
        if (stateOptional.isEmpty()) {
            return "多 Agent 编排未获取到有效结果，请补充更多任务背景后重试。";
        }
        return aiOpsService.extractFinalReport(stateOptional.get())
                .orElse("多 Agent 流程已完成，但未能生成可用解答。");
    }

    private boolean shouldUseOpsMode(RouteDecision decision) {
        String taskType = decision.getTaskType() == null ? "" : decision.getTaskType();
        if (taskType.contains("ai_ops")) return true;
        if (taskType.contains("multi_step")) return true;
        List<String> tools = decision.getRequiredTools();
        return tools != null && tools.stream().anyMatch(tool ->
                "queryPrometheusAlerts".equals(tool)
                        || "queryLogs".equals(tool)
                        || "getAvailableLogTopics".equals(tool));
    }
}
