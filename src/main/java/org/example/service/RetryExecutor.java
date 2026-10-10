package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.example.observability.AgentTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;

import java.util.concurrent.Callable;

/** Centralized bounded retry policy for transient dependency failures. */
@Component
public class RetryExecutor {
    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_DELAY_MS = 1000L;
    private final AgentTelemetry telemetry;
    private final MeterRegistry meters;
    private final Tracer tracer;

    public RetryExecutor(AgentTelemetry telemetry, MeterRegistry meters, Tracer tracer) {
        this.telemetry = telemetry;
        this.meters = meters;
        this.tracer = tracer;
    }

    public <T> T execute(String operation, Callable<T> action) {
        Throwable last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (AgentTelemetry.ObservedSpan span = telemetry.start("retry.attempt");
                 Tracer.SpanInScope ignored = span.scope()) {
                span.tag("retry.operation", operation).tag("retry.attempt", String.valueOf(attempt));
                T result = action.call();
                span.success();
                return result;
            } catch (Throwable error) {
                last = error;
                if (!isRetryable(error) || attempt == MAX_ATTEMPTS) throw propagate(error);
                long delay = INITIAL_DELAY_MS << (attempt - 1);
                meters.counter("agent.retry.total", "operation", operation,
                        "reason", retryReason(error)).increment();
                log.warn("Transient failure operation={}, attempt={}/{}, retryInMs={}, reason={}",
                        operation, attempt, MAX_ATTEMPTS, delay, rootMessage(error));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Retry interrupted", interrupted);
                }
            }
        }
        throw propagate(last);
    }

    private String retryReason(Throwable error) {
        String message = rootMessage(error).toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) return "timeout";
        if (message.contains("deadlock")) return "deadlock";
        if (message.contains("429") || message.contains("too many requests")) return "rate_limit";
        if (message.matches(".*\\b5(00|02|03|04)\\b.*")) return "server_error";
        return "connection";
    }

    private boolean isRetryable(Throwable error) {
        Throwable current = error;
        boolean transientFailure = false;
        while (current != null) {
            if (current instanceof IllegalArgumentException
                    || current instanceof SecurityException
                    || current instanceof org.springframework.security.access.AccessDeniedException) return false;
            String message = current.getMessage();
            if (message != null && message.matches("(?i).*\\b(400|401|403|404)\\b.*")) return false;
            if (current instanceof java.io.IOException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.sql.SQLTransientException
                    || current instanceof java.sql.SQLRecoverableException
                    || (message != null && message.matches("(?i).*(timeout|timed out|connection reset|connection refused|temporarily unavailable|deadlock|too many requests|\\b429\\b|\\b5(00|02|03|04)\\b).*"))) {
                transientFailure = true;
            }
            current = current.getCause();
        }
        return transientFailure;
    }

    private RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        return new RuntimeException(error);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
