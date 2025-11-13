package com.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible transport for the six bounded roles in the BuySense workflow. */
@Component
public class ModelPortAgentBridge implements AgentModelTransport {
    private final ModelPortProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final HttpClient http;
    private volatile String lastStatus;
    private volatile long lastLatencyMs;

    public ModelPortAgentBridge(ModelPortProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.getReadTimeout());
        this.client = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(factory)
                .build();
        this.lastStatus = properties.isEnabled() ? "configured" : "offline";
    }

    @Override
    public String mode() {
        return properties.isEnabled() ? "modelport" : "replay";
    }

    @Override
    public boolean roleEnabled(String role) {
        return !properties.isEnabled() || properties.isRoleEnabled(role);
    }

    public int maxCallsPerRun() {
        return properties.getMaxCallsPerRun();
    }

    @Override
    public AgentModelTransport.Description describe(String role) {
        boolean replay = !properties.isEnabled();
        return new AgentModelTransport.Description(
                replay ? "buysense-replay" : "modelport",
                replay ? "replay-" + role : properties.modelForRole(role),
                true);
    }

    @Override
    public AgentModelTransport.Completion complete(AgentModelTransport.Request request) {
        if (!properties.isEnabled()) {
            return replayCompletion(request);
        }
        if (!properties.isRoleEnabled(request.role())) {
            throw new AgentModelTransport.UnavailableException(
                    "model role is disabled: " + request.role());
        }
        long started = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.modelForRole(request.role()));
            body.put("temperature", 0);
            body.put("max_tokens", properties.getMaxTokensPerCall());
            body.put("messages", request.messages().stream()
                    .map(this::transportMessage).toList());
            body.put("tools", request.tools().stream()
                    .map(this::transportTool).toList());
            if (!"default".equals(properties.getThinkingMode())) {
                body.put("thinking", Map.of("type", properties.getThinkingMode()));
            }
            // DeepSeek thinking mode rejects forced tool choice and requires
            // reasoning-content replay. These bounded roles use non-thinking
            // mode so the artifact tool can remain mandatory and inexpensive.
            if ("disabled".equals(properties.getThinkingMode())) {
                body.put("tool_choice", "required");
            }
            body.put("parallel_tool_calls", false);

            Duration timeout = request.timeout().compareTo(properties.getReadTimeout()) < 0
                    ? request.timeout() : properties.getReadTimeout();
            timeout = Duration.ofMillis(Math.max(1, timeout.toMillis()));
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
            requestFactory.setReadTimeout(timeout);
            JsonNode response = client.mutate().requestFactory(requestFactory).build().post()
                    .uri("/v1/chat/completions")
                    .header("x-api-key", properties.getApiKey())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Idempotency-Key",
                            request.runId() + "-" + request.role() + "-turn-" + request.turn())
                    .header("x-modelport-traffic-class", "business")
                    .header("x-modelport-hybrid-mode", "local_strict")
                    .header("x-modelport-data-classification", "internal")
                    .header("x-modelport-agent-role", request.role())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode message = response == null
                    ? mapper.createObjectNode()
                    : response.path("choices").path(0).path("message");
            List<AgentModelTransport.ToolCall> toolCalls = new ArrayList<>();
            message.path("tool_calls").forEach(item -> {
                String id = item.path("id").asText();
                String name = item.path("function").path("name").asText();
                String arguments = item.path("function").path("arguments").asText("{}");
                toolCalls.add(new AgentModelTransport.ToolCall(
                        id, name, parseToolArguments(arguments)));
            });
            int inputTokens = response == null ? 0
                    : response.path("usage").path("prompt_tokens").asInt();
            int outputTokens = response == null ? 0
                    : response.path("usage").path("completion_tokens").asInt();
            int totalTokens = response == null ? 0
                    : response.path("usage").path("total_tokens")
                            .asInt(inputTokens + outputTokens);
            lastStatus = "up";
            lastLatencyMs = elapsed(started);
            return new AgentModelTransport.Completion(
                    message.path("content").isTextual()
                            ? message.path("content").asText() : null,
                    toolCalls,
                    new AgentModelTransport.Usage(inputTokens, outputTokens, totalTokens));
        } catch (AgentModelTransport.UnavailableException error) {
            throw error;
        } catch (Exception error) {
            lastStatus = "degraded";
            lastLatencyMs = elapsed(started);
            throw new AgentModelTransport.UnavailableException(
                    "model transport failed: " + error.getClass().getSimpleName(),
                    error instanceof org.springframework.web.client.ResourceAccessException
                            || error instanceof org.springframework.web.client.RestClientResponseException responseError
                            && responseError.getStatusCode().is5xxServerError());
        }
    }

    private AgentModelTransport.Completion replayCompletion(
            AgentModelTransport.Request request
    ) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("payload", replayPayload(request));
        return new AgentModelTransport.Completion(
                null,
                List.of(new AgentModelTransport.ToolCall(
                        "call-" + request.role(),
                        "publish_artifact",
                        arguments)),
                AgentModelTransport.Usage.ZERO);
    }

    private String replayPayload(AgentModelTransport.Request request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            AgentModelTransport.Message message = request.messages().get(index);
            if (!"user".equals(message.role()) || message.content() == null) continue;
            try {
                JsonNode envelope = mapper.readTree(message.content());
                JsonNode context = envelope.path("authoritativeContext");
                if (!context.isMissingNode()) return json(context);
            } catch (Exception ignored) {
                // A malformed prompt still receives a valid replay tool payload.
            }
        }
        return "{}";
    }

    private Map<String, Object> transportMessage(AgentModelTransport.Message message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("role", message.role());
        if (message.content() != null) value.put("content", message.content());
        if (message.toolCallId() != null) value.put("tool_call_id", message.toolCallId());
        if (!message.toolCalls().isEmpty()) {
            value.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", call.name(),
                            "arguments", json(call.arguments())))).toList());
        }
        return value;
    }

    private Map<String, Object> transportTool(AgentModelTransport.ToolDefinition definition) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", definition.name(),
                        "description", definition.description(),
                        "parameters", mapper.convertValue(definition.parameters(), Object.class)));
    }

    private JsonNode parseToolArguments(String value) {
        try {
            JsonNode parsed = mapper.readTree(value);
            if (parsed != null && parsed.isObject()) return parsed;
        } catch (Exception ignored) {
            // The role loop returns a validation error to the model as the tool result.
        }
        return mapper.createObjectNode().put("_invalidArguments", value);
    }
    public Status status() {
        return new Status(
                properties.isEnabled() ? "modelport" : "replay",
                properties.isEnabled() ? properties.getModel() : "deterministic-replay",
                lastStatus,
                lastLatencyMs);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("model input serialization failed", error);
        }
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record RoleCall(
            String role,
            boolean attempted,
            boolean success,
            JsonNode proposal,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            long latencyMs,
            String error
    ) {
        public RoleCall(
                String role,
                boolean attempted,
                boolean success,
                JsonNode proposal,
                int totalTokens,
                long latencyMs,
                String error
        ) {
            this(role, attempted, success, proposal, 0, 0, totalTokens, latencyMs, error);
        }

        public static RoleCall disabled(String role) {
            return new RoleCall(role, false, false, null, 0, 0, null);
        }

        static RoleCall failure(String role, long latencyMs, String error) {
            return new RoleCall(role, true, false, null, 0, latencyMs, error);
        }

        public String text(String field) {
            if (!success || proposal == null) return null;
            String value = proposal.path(field).asText(null);
            return value == null || value.isBlank() ? null : value.trim();
        }

        public List<String> strings(String field, int maximum) {
            if (!success || proposal == null || !proposal.path(field).isArray()) return List.of();
            List<String> values = new ArrayList<>();
            proposal.path(field).forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank() && values.size() < maximum) {
                    values.add(item.asText());
                }
            });
            return List.copyOf(values);
        }
    }

    public record Status(String mode, String model, String status, long latencyMs) {
    }
}
