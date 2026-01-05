package com.buysense.retail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("buysense.retail-provider")
public record RetailProviderProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        boolean fallbackEnabled,
        boolean allowInsecureHttp,
        Duration connectTimeout,
        Duration readTimeout,
        int maxResponseBytes
) {
    public RetailProviderProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 1_048_576 : maxResponseBytes;
        if (enabled && baseUrl.isBlank()) {
            throw new IllegalArgumentException("enabled retail provider requires a base URL");
        }
        if (apiKey.contains("\r") || apiKey.contains("\n") || apiKey.length() > 4096) {
            throw new IllegalArgumentException("retail provider API key contains invalid characters");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("retail provider timeouts must be positive");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("retail provider response limit is out of range");
        }
    }
}
