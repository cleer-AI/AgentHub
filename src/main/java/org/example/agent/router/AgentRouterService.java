package org.example.agent.router;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.observability.AgentTelemetry;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AgentRouterService {

    private static final Logger logger = LoggerFactory.getLogger(AgentRouterService.class);
    private static final double MIN_CONFIDENCE = 0.65;
    private static final int MAX_HISTORY_CHARS = 1600;

    private static final Set<String> LOCAL_TOOLS = Set.of(
            "getCurrentDateTime",
            "queryInternalDocs",
            "queryOrderInfo",
            "queryOrderTracking",
            "queryPrometheusAlerts",
            "queryLogs",
            "list_skills",
            "read_skill",
            "list_skill_resources",
            "read_resource",
            "execute_script",
            "calculate_individual_income_tax"
    );

    @Autowired
    private ChatService chatService;

    @Autowired
    private AgentTelemetry telemetry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RouteDecision route(String question, List<Map<String, String>> history) {
        long started = System.nanoTime();
        RouteDecision ruleDecision = ruleBasedRoute(question, history);
        if (ruleDecision.getRoute() == AgentRoute.CLARIFY || ruleDecision.getConfidence() >= 0.9) {
            recordRoute(ruleDecision, started, "rule");
            return ruleDecision;
        }

        try {
            RouteDecision llmDecision = callLlmRouter(question, history, ruleDecision);
            RouteDecision validated = validate(llmDecision, ruleDecision);
            recordRoute(validated, started, "llm");
            return validated;
        } catch (Exception error) {
            logger.warn("RouterAgent LLM 判断失败，使用规则兜底: {}", error.getMessage());
            RouteDecision fallback = validate(ruleDecision, RouteDecision.react(
                    "general_qa", Complexity.LOW, List.of(), 0.5, "路由模型失败，默认使用 ReAct"));
            recordRoute(fallback, started, "fallback");
            return fallback;
        }
    }

    private RouteDecision ruleBasedRoute(String rawQuestion, List<Map<String, String>> history) {
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        String lower = question.toLowerCase(Locale.ROOT);

        if (question.isBlank()) {
            return RouteDecision.clarify("unknown", "请先输入你想咨询或处理的问题。", "用户问题为空");
        }

        if (containsAny(question, "订单", "物流", "快递", "运单") && !containsLikelyIdentifier(question)) {
            return RouteDecision.clarify("order_query", "请提供订单号或物流单号，我再帮你查询。", "订单/物流查询缺少必要编号");
        }

        if (containsAny(question, "分析", "排查", "诊断", "根因", "报告", "复盘", "对比", "总结", "方案", "规划")
                && containsAny(question, "日志", "指标", "告警", "服务", "接口", "文档", "文件", "知识库", "性能", "错误", "异常", "多份")) {
            return RouteDecision.planAndExecute("multi_step_analysis",
                    inferRequiredTools(question),
                    0.92,
                    "任务包含分析/诊断/报告等多阶段步骤，并依赖工具结果逐步推进");
        }

        if (containsAny(question, "cpu", "内存", "磁盘", "延迟", "超时", "错误率", "5xx", "qps", "pod", "k8s", "告警", "日志", "metrics", "prometheus")) {
            if (containsAny(question, "为什么", "原因", "根因", "排查", "诊断", "恢复", "修复")) {
                return RouteDecision.planAndExecute("ai_ops",
                        inferRequiredTools(question),
                        0.9,
                        "运维诊断通常需要先查指标/日志，再归因和给出处理建议");
            }
            return RouteDecision.react("ai_ops_query", Complexity.MEDIUM, inferRequiredTools(question), 0.8, "运维类单步查询优先使用 ReAct");
        }

        if (containsAny(question, "现在几点", "当前时间", "今天日期", "星期几", "时间")) {
            return RouteDecision.react("time_query", Complexity.LOW, List.of("getCurrentDateTime"), 0.95, "时间查询是单步工具任务");
        }

        if (containsAny(question, "个人所得税", "个税", "纳税")) {
            return RouteDecision.react("skill_task", Complexity.MEDIUM, List.of("read_skill", "execute_script", "calculate_individual_income_tax"), 0.9, "匹配本地税务计算 Skill");
        }

        if (containsAny(question, "订单", "物流", "快递", "运单")) {
            return RouteDecision.react("order_query", Complexity.LOW, inferRequiredTools(question), 0.9, "订单/物流查询是明确的单步工具任务");
        }

        if (containsAny(question, "文档", "知识库", "说明书", "使用指南", "怎么用", "FAQ", "常见问题")) {
            return RouteDecision.react("document_qa", Complexity.MEDIUM, List.of("queryInternalDocs"), 0.78, "文档问答可由 ReAct 根据检索结果即时决策");
        }

        return RouteDecision.react("general_qa", Complexity.LOW, List.of(), 0.7, "未命中复杂任务规则，默认使用 ReAct");
    }

    private RouteDecision callLlmRouter(String question, List<Map<String, String>> history, RouteDecision ruleDecision) throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel routerModel = chatService.createChatModel(dashScopeApi, 0.1, 800, 0.8);
        String toolNames = buildToolNames();
        String system = """
                你是 Router Agent，只做路由决策，不回答用户问题。
                你必须只输出一个 JSON 对象，不要输出 Markdown，不要解释。

                JSON Schema:
                {
                  "route": "REACT | PLAN_AND_EXECUTE | CLARIFY",
                  "taskType": "general_qa | time_query | order_query | document_qa | ai_ops | file_analysis | skill_task | multi_step_analysis | unknown",
                  "complexity": "LOW | MEDIUM | HIGH",
                  "requiredTools": ["工具名"],
                  "confidence": 0.0,
                  "reason": "简短中文原因",
                  "clarifyQuestion": "需要澄清时填写，否则为空字符串",
                  "allowUpgrade": true
                }

                路由规则:
                - 简单问答、单步工具、需要根据工具结果即时决策的任务，选择 REACT。
                - 多阶段任务、强前后依赖、需要先计划再执行再校验的任务，选择 PLAN_AND_EXECUTE。
                - 意图不明确或关键参数缺失，选择 CLARIFY。
                - 置信度低于 0.65 时选择 REACT。
                - route 只能使用白名单: REACT, PLAN_AND_EXECUTE, CLARIFY。
                - requiredTools 只能从可用工具列表选择。
                """;
        String user = "可用工具: " + toolNames + "\n\n"
                + "规则初判: " + objectMapper.writeValueAsString(ruleDecision) + "\n\n"
                + "历史上下文摘要:\n" + compactHistory(history) + "\n\n"
                + "用户问题:\n" + question;
        String content = telemetry.observe("agent.router.llm", () ->
                routerModel.call(new Prompt(new SystemMessage(system), new UserMessage(user)))
                        .getResult().getOutput().getText());
        return objectMapper.readValue(stripJson(content), RouteDecision.class);
    }

    private RouteDecision validate(RouteDecision candidate, RouteDecision fallback) {
        if (candidate == null || candidate.getRoute() == null) {
            return fallback;
        }
        if (candidate.getConfidence() < MIN_CONFIDENCE && candidate.getRoute() != AgentRoute.CLARIFY) {
            candidate.setRoute(AgentRoute.REACT);
            candidate.setReason("路由置信度不足，默认进入 ReAct；原因为：" + nullSafe(candidate.getReason()));
        }
        if (candidate.getRoute() == AgentRoute.CLARIFY
                && (candidate.getClarifyQuestion() == null || candidate.getClarifyQuestion().isBlank())) {
            candidate.setRoute(AgentRoute.REACT);
            candidate.setReason("澄清问题缺失，降级进入 ReAct");
        }
        if (candidate.getTaskType() == null || candidate.getTaskType().isBlank()) {
            candidate.setTaskType(fallback.getTaskType());
        }
        if (candidate.getComplexity() == null) {
            candidate.setComplexity(fallback.getComplexity());
        }
        candidate.setRequiredTools(filterTools(candidate.getRequiredTools()));
        if (candidate.getReason() == null || candidate.getReason().isBlank()) {
            candidate.setReason(fallback.getReason());
        }
        return candidate;
    }

    private List<String> inferRequiredTools(String question) {
        List<String> tools = new ArrayList<>();
        if (containsAny(question, "时间", "日期", "今天", "星期")) tools.add("getCurrentDateTime");
        if (containsAny(question, "订单")) tools.add("queryOrderInfo");
        if (containsAny(question, "物流", "快递", "运单")) tools.add("queryOrderTracking");
        if (containsAny(question, "文档", "知识库", "说明书", "使用指南", "FAQ", "常见问题")) tools.add("queryInternalDocs");
        if (containsAny(question, "cpu", "内存", "磁盘", "延迟", "qps", "错误率", "指标", "metrics", "prometheus")) tools.add("queryPrometheusAlerts");
        if (containsAny(question, "日志", "错误", "异常", "堆栈", "trace")) tools.add("queryLogs");
        return tools;
    }

    private String buildToolNames() {
        Set<String> names = new HashSet<>(LOCAL_TOOLS);
        for (ToolCallback callback : chatService.getToolCallbacks()) {
            names.add(callback.getToolDefinition().name());
        }
        return String.join(", ", names);
    }

    private List<String> filterTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        Set<String> allowed = new HashSet<>(LOCAL_TOOLS);
        for (ToolCallback callback : chatService.getToolCallbacks()) {
            allowed.add(callback.getToolDefinition().name());
        }
        return tools.stream()
                .filter(tool -> tool != null && allowed.contains(tool))
                .distinct()
                .toList();
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        return Arrays.stream(keywords).anyMatch(text::contains);
    }

    private boolean containsLikelyIdentifier(String text) {
        return text != null && text.matches(".*[A-Za-z0-9][A-Za-z0-9_-]{5,}.*");
    }

    private String compactHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) return "无";
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            Map<String, String> message = history.get(i);
            builder.append(message.getOrDefault("role", "unknown"))
                    .append(": ")
                    .append(message.getOrDefault("content", ""))
                    .append("\n");
        }
        if (builder.length() > MAX_HISTORY_CHARS) {
            return builder.substring(builder.length() - MAX_HISTORY_CHARS);
        }
        return builder.toString();
    }

    private String stripJson(String content) {
        if (content == null) return "{}";
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private void recordRoute(RouteDecision decision, long startedNanos, String source) {
        String route = decision.getRoute() == null ? "UNKNOWN" : decision.getRoute().name();
        String taskType = decision.getTaskType() == null ? "unknown" : decision.getTaskType();
        String complexity = decision.getComplexity() == null ? "UNKNOWN" : decision.getComplexity().name();
        telemetry.increment("agent.router.total", "route", route);
        telemetry.record("agent.router.duration", System.nanoTime() - startedNanos, "source", source);
        logger.info("RouterAgent route={} taskType={} complexity={} confidence={} source={} reason={}",
                route, taskType, complexity, decision.getConfidence(), source, decision.getReason());
    }
}
