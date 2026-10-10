package org.example.agent.router;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RouteDecision {
    private AgentRoute route = AgentRoute.REACT;
    private String taskType = "general_qa";
    private Complexity complexity = Complexity.LOW;
    private List<String> requiredTools = new ArrayList<>();
    private double confidence = 0.5;
    private String reason = "默认使用 ReAct 处理";
    private String clarifyQuestion;
    private boolean allowUpgrade = true;

    public static RouteDecision react(String taskType, Complexity complexity, List<String> requiredTools, double confidence, String reason) {
        RouteDecision decision = new RouteDecision();
        decision.setRoute(AgentRoute.REACT);
        decision.setTaskType(taskType);
        decision.setComplexity(complexity);
        decision.setRequiredTools(requiredTools == null ? new ArrayList<>() : requiredTools);
        decision.setConfidence(confidence);
        decision.setReason(reason);
        decision.setAllowUpgrade(true);
        return decision;
    }

    public static RouteDecision planAndExecute(String taskType, List<String> requiredTools, double confidence, String reason) {
        RouteDecision decision = new RouteDecision();
        decision.setRoute(AgentRoute.PLAN_AND_EXECUTE);
        decision.setTaskType(taskType);
        decision.setComplexity(Complexity.HIGH);
        decision.setRequiredTools(requiredTools == null ? new ArrayList<>() : requiredTools);
        decision.setConfidence(confidence);
        decision.setReason(reason);
        decision.setAllowUpgrade(false);
        return decision;
    }

    public static RouteDecision clarify(String taskType, String clarifyQuestion, String reason) {
        RouteDecision decision = new RouteDecision();
        decision.setRoute(AgentRoute.CLARIFY);
        decision.setTaskType(taskType);
        decision.setComplexity(Complexity.MEDIUM);
        decision.setConfidence(0.95);
        decision.setReason(reason);
        decision.setClarifyQuestion(clarifyQuestion);
        decision.setAllowUpgrade(false);
        return decision;
    }
}
