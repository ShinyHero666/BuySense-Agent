package com.buysense.retail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class RetailSourceState {
    private final String configuredMode;
    private final String providerId;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();
    private volatile String effectiveSource;
    private volatile String status = "up";
    private volatile boolean fallbackActive;
    private volatile String version;
    private volatile String effectiveProviderId;
    private volatile String lastErrorCode;

    public RetailSourceState(String configuredMode, String providerId) {
        this.configuredMode = configuredMode;
        this.providerId = providerId;
        this.effectiveSource = configuredMode.equals("static") ? "local_snapshot" : "remote_provider";
        this.effectiveProviderId = providerId;
    }

    public void requestStarted() {
        requests.incrementAndGet();
    }

    public synchronized void succeeded(DataSourceMetadata metadata) {
        effectiveSource = metadata.source();
        status = "up";
        fallbackActive = false;
        version = metadata.sourceVersion();
        effectiveProviderId = metadata.providerId();
        lastErrorCode = null;
    }

    public synchronized void failed(String errorCode) {
        errors.incrementAndGet();
        effectiveSource = "unavailable";
        status = "down";
        fallbackActive = false;
        version = null;
        effectiveProviderId = null;
        lastErrorCode = errorCode;
    }

    public synchronized void fellBack(DataSourceMetadata metadata, String errorCode) {
        fallbacks.incrementAndGet();
        effectiveSource = metadata.source();
        status = "degraded";
        fallbackActive = true;
        version = metadata.sourceVersion();
        effectiveProviderId = metadata.providerId();
        lastErrorCode = errorCode;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configuredMode", configuredMode);
        result.put("effectiveSource", effectiveSource);
        result.put("status", status);
        result.put("fallbackActive", fallbackActive);
        result.put("version", version);
        result.put("providerId", providerId);
        result.put("effectiveProviderId", effectiveProviderId);
        result.put("telemetry", Map.of(
                "requests", requests.get(),
                "errors", errors.get(),
                "fallbacks", fallbacks.get()));
        if (lastErrorCode != null) result.put("lastErrorCode", lastErrorCode);
        return result;
    }

    public record DataSourceMetadata(String source, String sourceVersion, String providerId) {
    }
}
