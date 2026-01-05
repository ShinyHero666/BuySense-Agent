package com.buysense.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("buysense.modelport")
public class ModelPortProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:38082";
    private String apiKey = "dev-client-key";
    private String model = "qwen-default";
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(45);
    private int maxCallsPerRun = 6;
    private int maxTokensPerCall = 768;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxCallsPerRun() {
        return maxCallsPerRun;
    }

    public void setMaxCallsPerRun(int maxCallsPerRun) {
        this.maxCallsPerRun = maxCallsPerRun;
    }

    public int getMaxTokensPerCall() {
        return maxTokensPerCall;
    }

    public void setMaxTokensPerCall(int maxTokensPerCall) {
        this.maxTokensPerCall = maxTokensPerCall;
    }
}
