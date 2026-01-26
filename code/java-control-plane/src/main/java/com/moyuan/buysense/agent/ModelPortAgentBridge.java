package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.domain.DecisionResult;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ModelPortAgentBridge {
    private final ModelPortProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private volatile String lastStatus;
    private volatile long lastLatencyMs;

    public ModelPortAgentBridge(ModelPortProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.client = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
        this.lastStatus = properties.isEnabled() ? "configured" : "offline";
    }

    public RoleCall plan(String runId, String message) {
        if (!canCall(1)) return RoleCall.disabled("planner");
        String system = """
                You are the bounded planner for a 3C digital purchase-decision system.
                Return one strict JSON object with these fields:
                rewrittenQuery, intent, explanation.
                Preserve every explicit category, budget, no-ad and bundle requirement.
                Add only useful retrieval vocabulary and soft use-case preferences.
                Never invent product, price, stock or compatibility facts.
                Keep explanation under 60 Chinese characters.
                """;
        return call(runId, "planner", system, message, 1);
    }

    public RoleCall reviewDecision(String runId, String message, DecisionResult decision) {
        if (!canCall(2)) return RoleCall.disabled("critic");

        List<Map<String, Object>> candidates = decision.slate().stream().limit(5)
                .map(candidate -> Map.<String, Object>of(
                        "id", candidate.product().id(),
                        "name", candidate.product().name(),
                        "category", candidate.product().category(),
                        "price", candidate.product().price(),
                        "channels", candidate.channels(),
                        "sponsored", candidate.sponsored()))
                .toList();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("query", message);
        evidence.put("budget", decision.requirement().budget());
        evidence.put("requiredCategories", decision.requirement().requiredCategories());
        evidence.put("useCases", decision.requirement().useCases());
        evidence.put("sponsoredAllowed", decision.requirement().sponsoredAllowed());
        evidence.put("candidates", candidates);
        evidence.put("bundleCount", decision.bundles().size());

        String system = """
                You are the evidence critic for a 3C purchase-decision system.
                Deterministic code has already enforced budget, category, ad and compatibility rules.
                Return one strict JSON object with:
                verdict: APPROVE, RETRIEVE or CLARIFY;
                explanation: evidence-grounded Chinese text under 80 characters;
                supplementaryQuery: extra retrieval terms only when verdict is RETRIEVE;
                clarificationQuestion: one user question only when verdict is CLARIFY.
                Do not change hard constraints and do not introduce facts absent from the evidence.
                Use CLARIFY only when a missing hard input makes a safe decision impossible.
                A bundle with explicit categories, budget and feasible candidates is complete enough.
                Missing optional preferences are not grounds for CLARIFY.
                When candidates exist and bundleCount is positive, prefer APPROVE over CLARIFY.
                """;
        return call(runId, "critic", system, objectMapper.valueToTree(evidence).toString(), 2);
    }

    public RoleCall analyzeIntent(String runId, String message) {
        return plan(runId, message);
    }

    public RoleCall explainDecision(String runId, String message, DecisionResult decision) {
        return reviewDecision(runId, message, decision);
    }

    public DecisionResult.ModelRuntime runtime(
            ExecutionRouter.Route route,
            RoleCall planner,
            RoleCall critic,
            boolean replanned
    ) {
        return runtime(route, planner, critic, replanned, true);
    }

    public DecisionResult.ModelRuntime runtime(
            ExecutionRouter.Route route,
            RoleCall planner,
            RoleCall critic,
            boolean replanned,
            boolean acceptCriticClarification
    ) {
        List<RoleCall> attempted = List.of(planner, critic).stream()
                .filter(RoleCall::attempted)
                .toList();
        int modelCalls = attempted.size();
        int fallbackCount = (int) attempted.stream().filter(call -> !call.success()).count();
        int totalTokens = attempted.stream().mapToInt(RoleCall::totalTokens).sum();
        long latencyMs = attempted.stream().mapToLong(RoleCall::latencyMs).sum();
        boolean rejectedCriticClarification = critic.success()
                && "CLARIFY".equalsIgnoreCase(critic.verdict())
                && !acceptCriticClarification;
        String explanation = critic.success() && !rejectedCriticClarification
                ? critic.explanation()
                : planner.success() ? planner.explanation() : null;
        String clarification = acceptCriticClarification
                && critic.success() && hasText(critic.clarificationQuestion())
                ? critic.clarificationQuestion()
                : route.clarificationQuestion();

        return new DecisionResult.ModelRuntime(
                route.mode().name().toLowerCase(Locale.ROOT),
                properties.isEnabled() ? properties.getModel() : "deterministic-sar-engine",
                modelCalls,
                fallbackCount,
                totalTokens,
                latencyMs,
                planner.success() ? planner.rewrittenQuery() : null,
                planner.success() ? planner.intent() : null,
                explanation,
                route.reasons(),
                critic.success() ? critic.verdict() : null,
                replanned,
                clarification);
    }

    public DecisionResult.ModelRuntime runtime(RoleCall planner, RoleCall critic) {
        ExecutionRouter.Route compatibilityRoute = new ExecutionRouter.Route(
                ExecutionRouter.Mode.HYBRID,
                List.of("legacy_bridge_call"),
                false,
                null);
        return runtime(compatibilityRoute, planner, critic, false);
    }

    public Status status() {
        return new Status(
                properties.isEnabled() ? "modelport" : "offline",
                properties.isEnabled() ? properties.getModel() : "deterministic-sar-engine",
                lastStatus,
                lastLatencyMs);
    }

    private boolean canCall(int ordinal) {
        return properties.isEnabled() && properties.getMaxCallsPerRun() >= ordinal;
    }

    private RoleCall call(
            String runId,
            String role,
            String system,
            String user,
            int ordinal
    ) {
        long started = System.nanoTime();
        try {
            JsonNode response = client.post()
                    .uri("/v1/chat/completions")
                    .header("x-api-key", properties.getApiKey())
                    .header("Idempotency-Key", runId + "-" + role + "-" + ordinal)
                    .body(Map.of(
                            "model", properties.getModel(),
                            "temperature", 0,
                            "max_tokens", properties.getMaxTokensPerCall(),
                            "messages", List.of(
                                    Map.of("role", "system", "content", system),
                                    Map.of("role", "user", "content", user))))
                    .retrieve()
                    .body(JsonNode.class);
            long latency = elapsed(started);
            String content = response == null
                    ? ""
                    : response.path("choices").path(0).path("message").path("content").asText("");
            JsonNode payload = parseObject(content);
            if (payload == null) {
                lastStatus = "degraded";
                lastLatencyMs = latency;
                return RoleCall.failure(role, latency, "model returned non-JSON content");
            }
            int totalTokens = response.path("usage").path("total_tokens").asInt(
                    response.path("usage").path("prompt_tokens").asInt()
                            + response.path("usage").path("completion_tokens").asInt());
            lastStatus = "up";
            lastLatencyMs = latency;
            return new RoleCall(
                    role,
                    true,
                    true,
                    text(payload, "rewrittenQuery"),
                    text(payload, "intent"),
                    text(payload, "explanation"),
                    text(payload, "verdict"),
                    text(payload, "supplementaryQuery"),
                    text(payload, "clarificationQuestion"),
                    totalTokens,
                    latency,
                    null);
        } catch (Exception error) {
            long latency = elapsed(started);
            lastStatus = "degraded";
            lastLatencyMs = latency;
            return RoleCall.failure(
                    role,
                    latency,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private JsonNode parseObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode parsed = objectMapper.readTree(content.substring(start, end + 1));
            return parsed.isObject() ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
            String rewrittenQuery,
            String intent,
            String explanation,
            String verdict,
            String supplementaryQuery,
            String clarificationQuestion,
            int totalTokens,
            long latencyMs,
            String error
    ) {
        public static RoleCall disabled(String role) {
            return new RoleCall(
                    role, false, false, null, null, null, null, null, null, 0, 0, null);
        }

        static RoleCall failure(String role, long latencyMs, String error) {
            return new RoleCall(
                    role, true, false, null, null, null, null, null, null, 0, latencyMs, error);
        }
    }

    public record Status(String mode, String model, String status, long latencyMs) {
    }
}
