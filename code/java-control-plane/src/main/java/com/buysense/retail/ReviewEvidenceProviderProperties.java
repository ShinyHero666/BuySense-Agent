package com.buysense.retail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("buysense.review-provider")
public record ReviewEvidenceProviderProperties(
        boolean enabled,
        String baseUrl,
        String queryPath,
        String apiKey,
        boolean fallbackEnabled,
        boolean allowInsecureHttp,
        Duration connectTimeout,
        Duration readTimeout,
        int maxResponseBytes
) {
    public ReviewEvidenceProviderProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        queryPath = queryPath == null || queryPath.isBlank()
                ? "/v1/reviews/query" : queryPath.trim();
        apiKey = apiKey == null ? "" : apiKey;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 1_048_576 : maxResponseBytes;
        if (enabled && baseUrl.isBlank()) {
            throw new IllegalArgumentException("enabled review provider requires a base URL");
        }
        if (!queryPath.startsWith("/") || queryPath.contains("?") || queryPath.contains("#")
                || queryPath.contains("..") || queryPath.length() > 256) {
            throw new IllegalArgumentException("review provider query path is invalid");
        }
        if (apiKey.contains("\r") || apiKey.contains("\n") || apiKey.length() > 4096) {
            throw new IllegalArgumentException("review provider API key contains invalid characters");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("review provider timeouts must be positive");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("review provider response limit is out of range");
        }
    }
}
