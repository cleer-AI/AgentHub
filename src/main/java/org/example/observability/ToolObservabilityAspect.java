package org.example.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ToolObservabilityAspect {
    private final AgentTelemetry telemetry;
    private final AgentRuntimeGuard guard;
    private final Tracer tracer;
    private final MeterRegistry meters;

    public ToolObservabilityAspect(AgentTelemetry telemetry, AgentRuntimeGuard guard,
                                   Tracer tracer, MeterRegistry meters) {
        this.telemetry = telemetry;
        this.guard = guard;
        this.tracer = tracer;
        this.meters = meters;
    }

    @Around("@annotation(tool)")
    public Object observeTool(ProceedingJoinPoint invocation, Tool tool) throws Throwable {
        String configuredName = tool.name();
        String toolName = configuredName == null || configuredName.isBlank()
                ? invocation.getSignature().getName() : configuredName;
        String traceId = tracer.currentSpan() == null ? "none" : tracer.currentSpan().context().traceId();
        guard.beforeTool(traceId, toolName);
        try (AgentTelemetry.ObservedSpan span = telemetry.start("tool." + toolName);
             Tracer.SpanInScope ignored = span.scope()) {
            span.tag("tool.name", toolName).tag("tool.retry_count", "0");
            try {
                Object result = invocation.proceed();
                String resultText = result == null ? "" : result.toString();
                String status = resultText.contains("\"status\": \"error\"")
                        || resultText.contains("\"success\" : false") ? "failed" :
                        resultText.contains("no_results") ? "empty" : "success";
                span.tag("tool.status", status).success();
                meters.counter("agent.tool.call.total", "tool", toolName, "status", status).increment();
                return result;
            } catch (Throwable error) {
                span.tag("tool.status", "failed").error(error);
                meters.counter("agent.tool.call.total", "tool", toolName, "status", "failed").increment();
                throw error;
            }
        }
    }
}
