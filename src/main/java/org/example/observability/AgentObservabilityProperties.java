package org.example.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "observability")
public class AgentObservabilityProperties {
    private final Agent agent = new Agent();
    private final Sse sse = new Sse();
    private final Privacy privacy = new Privacy();

    public Agent getAgent() { return agent; }
    public Sse getSse() { return sse; }
    public Privacy getPrivacy() { return privacy; }

    public static class Agent {
        private Duration maxDuration = Duration.ofMinutes(4);
        private int maxModelCalls = 12;
        private int maxToolCalls = 20;
        private int maxSameToolCalls = 3;
        public Duration getMaxDuration() { return maxDuration; }
        public void setMaxDuration(Duration maxDuration) { this.maxDuration = maxDuration; }
        public int getMaxModelCalls() { return maxModelCalls; }
        public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
        public int getMaxSameToolCalls() { return maxSameToolCalls; }
        public void setMaxSameToolCalls(int maxSameToolCalls) { this.maxSameToolCalls = maxSameToolCalls; }
    }

    public static class Sse {
        private Duration timeout = Duration.ofMinutes(5);
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Privacy {
        private String hmacSecret;
        public String getHmacSecret() { return hmacSecret; }
        public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    }
}
