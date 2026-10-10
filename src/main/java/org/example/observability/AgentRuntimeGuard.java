package org.example.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentRuntimeGuard {
    private final AgentObservabilityProperties properties;
    private final MeterRegistry meters;
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public AgentRuntimeGuard(AgentObservabilityProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
    }

    public void start(String traceId) { runs.put(traceId, new RunState()); }
    public void finish(String traceId) { runs.remove(traceId); }

    public void beforeTool(String traceId, String toolName) {
        RunState state = runs.get(traceId);
        if (state == null) return;
        enforceDuration(traceId, state);
        if (state.totalTools.incrementAndGet() > properties.getAgent().getMaxToolCalls()) {
            reject("max_tool_calls");
        }
        int sameToolCalls = state.tools.computeIfAbsent(toolName, ignored -> new AtomicInteger()).incrementAndGet();
        if (sameToolCalls > properties.getAgent().getMaxSameToolCalls()) {
            reject("max_same_tool_calls");
        }
    }

    private void enforceDuration(String traceId, RunState state) {
        if (Instant.now().isAfter(state.started.plus(properties.getAgent().getMaxDuration()))) {
            finish(traceId);
            reject("max_duration");
        }
    }

    private void reject(String reason) {
        meters.counter("agent.loop.limit.total", "reason", reason).increment();
        throw new AgentLimitExceededException(reason);
    }

    private static final class RunState {
        private final Instant started = Instant.now();
        private final AtomicInteger totalTools = new AtomicInteger();
        private final Map<String, AtomicInteger> tools = new ConcurrentHashMap<>();
    }

    public static class AgentLimitExceededException extends RuntimeException {
        public AgentLimitExceededException(String reason) { super("Agent runtime limit exceeded: " + reason); }
    }
}
