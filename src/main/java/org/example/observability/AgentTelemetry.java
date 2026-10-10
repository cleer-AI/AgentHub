package org.example.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Component
public class AgentTelemetry {
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final byte[] hmacKey;

    public AgentTelemetry(Tracer tracer, MeterRegistry meters, AgentObservabilityProperties properties) {
        this.tracer = tracer;
        this.meters = meters;
        this.hmacKey = properties.getPrivacy().getHmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    public ObservedSpan start(String name) {
        Span span = tracer.nextSpan().name(name).start();
        return new ObservedSpan(name, span, tracer, meters);
    }

    public <T> T observe(String name, Callable<T> action) {
        try (ObservedSpan span = start(name); Tracer.SpanInScope ignored = span.scope()) {
            try {
                T value = action.call();
                span.success();
                return value;
            } catch (Throwable error) {
                span.error(error);
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(error);
            }
        }
    }

    public String hash(String value) {
        if (value == null || value.isBlank()) return "none";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception error) {
            return "hash-error";
        }
    }

    public void increment(String metric, String key, String value) {
        Counter.builder(metric).tag(key, value).register(meters).increment();
    }

    public void record(String metric, long durationNanos, String key, String value) {
        Timer.builder(metric).tag(key, value).register(meters)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public static final class ObservedSpan implements AutoCloseable {
        private final String name;
        private final Span span;
        private final Tracer tracer;
        private final MeterRegistry meters;
        private final long startedNanos = System.nanoTime();
        private String status = "success";
        private boolean closed;

        private ObservedSpan(String name, Span span, Tracer tracer, MeterRegistry meters) {
            this.name = name;
            this.span = span;
            this.tracer = tracer;
            this.meters = meters;
        }

        public Tracer.SpanInScope scope() { return tracer.withSpan(span); }
        public String traceId() { return span.context().traceId(); }
        public ObservedSpan tag(String key, String value) {
            if (value != null) span.tag(key, value);
            return this;
        }
        public ObservedSpan event(String event) { span.event(event); return this; }
        public void success() { status = "success"; span.tag("status", status); }
        public void error(Throwable error) {
            status = "error";
            span.tag("status", status);
            span.tag("error.type", error.getClass().getSimpleName());
            span.error(error);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            long duration = System.nanoTime() - startedNanos;
            Timer.builder("agent.operation.duration")
                    .tag("operation", name).tag("status", status)
                    .register(meters).record(duration, TimeUnit.NANOSECONDS);
            Counter.builder("agent.operation.total")
                    .tag("operation", name).tag("status", status).register(meters).increment();
            span.end();
        }
    }
}
