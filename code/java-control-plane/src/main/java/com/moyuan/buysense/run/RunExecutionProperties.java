package com.moyuan.buysense.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("buysense.runs")
public record RunExecutionProperties(
        int maxConcurrent,
        int queueCapacity,
        int maxActivePerSession,
        int maxCreatedPerMinute,
        Duration leaseDuration,
        Duration proposalTtl,
        Duration terminalRetention
) {
    public RunExecutionProperties {
        maxConcurrent = positive(maxConcurrent, 16, "maxConcurrent");
        queueCapacity = positive(queueCapacity, 256, "queueCapacity");
        maxActivePerSession = positive(maxActivePerSession, 3, "maxActivePerSession");
        maxCreatedPerMinute = positive(maxCreatedPerMinute, 30, "maxCreatedPerMinute");
        leaseDuration = positive(leaseDuration, Duration.ofMinutes(2), "leaseDuration");
        proposalTtl = positive(proposalTtl, Duration.ofMinutes(15), "proposalTtl");
        terminalRetention = positive(terminalRetention, Duration.ofDays(30), "terminalRetention");
    }

    private static int positive(int value, int fallback, String field) {
        int resolved = value <= 0 ? fallback : value;
        if (resolved > 10_000) throw new IllegalArgumentException(field + " is out of range");
        return resolved;
    }

    private static Duration positive(Duration value, Duration fallback, String field) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return resolved;
    }
}