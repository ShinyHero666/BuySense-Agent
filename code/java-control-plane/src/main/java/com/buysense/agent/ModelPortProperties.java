package com.buysense.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("buysense.modelport")
public class ModelPortProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:38082";
    private String apiKey = "dev-client-key";
    private String model = "qwen-default";
    private Map<String, String> roleModels = Map.of();
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(45);
    private int maxCallsPerRun = 12;
    private int maxTokensPerCall = 768;
    private String thinkingMode = "disabled";
    private Set<String> disabledRoles = Set.of();

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
    public Map<String, String> getRoleModels() {
        return roleModels;
    }

    public void setRoleModels(Map<String, String> roleModels) {
        if (roleModels == null || roleModels.isEmpty()) {
            this.roleModels = Map.of();
            return;
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        roleModels.forEach((role, value) -> {
            if (role == null || role.isBlank() || value == null || value.isBlank()) {
                return;
            }
            normalized.put(
                    role.trim().toLowerCase(Locale.ROOT),
                    value.trim());
        });
        this.roleModels = Map.copyOf(normalized);
    }

    public String modelForRole(String role) {
        if (role == null || role.isBlank()) return model;
        return roleModels.getOrDefault(
                role.trim().toLowerCase(Locale.ROOT), model);
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
        if (maxCallsPerRun < 1) throw new IllegalArgumentException("maxCallsPerRun must be positive");
        this.maxCallsPerRun = maxCallsPerRun;
    }

    public int getMaxTokensPerCall() {
        return maxTokensPerCall;
    }

    public void setMaxTokensPerCall(int maxTokensPerCall) {
        this.maxTokensPerCall = maxTokensPerCall;
    }

    public String getThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(String thinkingMode) {
        String normalized = thinkingMode == null
                ? "default" : thinkingMode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("default", "enabled", "disabled").contains(normalized)) {
            throw new IllegalArgumentException(
                    "thinkingMode must be default, enabled, or disabled");
        }
        this.thinkingMode = normalized;
    }

    public Set<String> getDisabledRoles() {
        return disabledRoles;
    }

    public void setDisabledRoles(Set<String> disabledRoles) {
        if (disabledRoles == null || disabledRoles.isEmpty()) {
            this.disabledRoles = Set.of();
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        disabledRoles.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(normalized::add);
        this.disabledRoles = Set.copyOf(normalized);
    }

    public boolean isRoleEnabled(String role) {
        return enabled && !disabledRoles.contains(role.toLowerCase(Locale.ROOT));
    }

    public ModelPortProperties copy() {
        ModelPortProperties value = new ModelPortProperties();
        value.setEnabled(enabled);
        value.setBaseUrl(baseUrl);
        value.setApiKey(apiKey);
        value.setModel(model);
        value.setConnectTimeout(connectTimeout);
        value.setRoleModels(roleModels);
        value.setReadTimeout(readTimeout);
        value.setMaxCallsPerRun(maxCallsPerRun);
        value.setMaxTokensPerCall(maxTokensPerCall);
        value.setThinkingMode(thinkingMode);
        value.setDisabledRoles(disabledRoles);
        return value;
    }
}
