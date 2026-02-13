package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.DecisionResult;
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
public class ModelPortAgentBridge {
    private final ModelPortProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;
    private volatile String lastStatus;
    private volatile long lastLatencyMs;

    public ModelPortAgentBridge(ModelPortProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        HttpClient http = HttpClient.newBuilder()
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

    public RoleCall plan(String runId, String message, int ordinal) {
        String system = """
                你是购买决策系统的 Intent Router。只输出一个 JSON 对象：
                intent、query、requestedCategories、preferredBrands、useCases、channels、
                sponsoredAllowed、candidateBudget、reason。
                必须保留用户原始问题、显式预算、品类、品牌、用途和不要广告要求；
                search 必须保留，套装必须保留 recommendation，禁止编造商品事实。
                """;
        return call(runId, "intent_router", system, message, ordinal);
    }

    public RoleCall rank(
            String runId,
            String role,
            List<Candidate> candidates,
            int ordinal
    ) {
        List<Map<String, Object>> grounded = candidates.stream().map(candidate -> Map.<String, Object>of(
                "skuId", candidate.product().skuId(),
                "title", candidate.product().name(),
                "category", candidate.product().category(),
                "price", candidate.product().price(),
                "score", candidate.score(),
                "sponsored", candidate.sponsored())).toList();
        String system = """
                你是搜广推渠道排序角色。只输出 JSON：rankedSkuIds、rationaleBySku；
                只能重排输入中已有 SKU，不能新增商品、价格、库存或兼容性事实。
                """;
        return call(runId, role, system, json(Map.of("candidates", grounded)), ordinal);
    }

    public RoleCall reviewDecision(
            String runId,
            String message,
            DecisionResult decision,
            int ordinal
    ) {
        List<Map<String, Object>> candidates = decision.slate().stream().limit(12)
                .map(candidate -> Map.<String, Object>of(
                        "skuId", candidate.product().skuId(),
                        "category", candidate.product().category(),
                        "price", candidate.product().price(),
                        "sponsored", candidate.sponsored(),
                        "score", candidate.score()))
                .toList();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("query", message);
        evidence.put("budget", decision.requirement().budget());
        evidence.put("requestedCategories", decision.requirement().requiredCategories());
        evidence.put("useCases", decision.requirement().useCases());
        evidence.put("candidates", candidates);
        evidence.put("bundleCount", decision.bundles().size());
        evidence.put("deterministicViolations",
                decision.metrics().getOrDefault("criticViolations", List.of()));
        String system = """
                你是独立 Critic。确定性代码已经完成硬约束审核。
                只输出 JSON：verdict、rationale、additionalViolations。
                你不能删除确定性违规，只能在证据确实支持时补充：
                insufficient_review_confidence、weak_use_case_match、ambiguous_compatibility。
                """;
        return call(runId, "critic", system, json(evidence), ordinal);
    }

    public RoleCall compose(
            String runId,
            String deterministicMessage,
            boolean approved,
            int ordinal
    ) {
        String system = """
                你是 Lead，只基于输入证据组织最终中文说明。只输出 JSON：message、approved。
                approved 不得改变；不得新增数字，不得声称已下单、已创建订单、已支付或支付成功。
                商品行中出现“｜赞助”时必须原样保留，不能删除或改写为普通商品；推荐依据中出现“快充依据”、功率或协议时必须保留。
                只能调整语言顺序，不得删除输入中已有的推荐依据、取舍、预算利用率、兼容依据和交易边界。
                对比场景必须保留多个候选，不能合并成单个推荐；套装场景必须保留总价和兼容说明。
                """;
        return call(runId, "lead", system, json(Map.of(
                "message", deterministicMessage, "approved", approved)), ordinal);
    }

    public Status status() {
        return new Status(
                properties.isEnabled() ? "modelport" : "replay",
                properties.isEnabled() ? properties.getModel() : "deterministic-replay",
                lastStatus,
                lastLatencyMs);
    }

    private RoleCall call(
            String runId,
            String role,
            String system,
            String user,
            int ordinal
    ) {
        if (!properties.isEnabled() || ordinal > properties.getMaxCallsPerRun()) {
            return RoleCall.disabled(role);
        }
        long started = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getModel());
            body.put("temperature", 0);
            body.put("max_tokens", properties.getMaxTokensPerCall());
            body.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)));
            JsonNode response = client.post()
                    .uri("/v1/chat/completions")
                    .header("x-api-key", properties.getApiKey())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Idempotency-Key", runId + "-" + role + "-" + ordinal)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            long latency = elapsed(started);
            String content = response == null ? ""
                    : response.path("choices").path(0).path("message").path("content").asText("");
            JsonNode proposal = parseObject(content);
            if (proposal == null) {
                lastStatus = "degraded";
                lastLatencyMs = latency;
                return RoleCall.failure(role, latency, "model returned non-JSON content");
            }
            int totalTokens = response.path("usage").path("total_tokens").asInt(
                    response.path("usage").path("prompt_tokens").asInt()
                            + response.path("usage").path("completion_tokens").asInt());
            lastStatus = "up";
            lastLatencyMs = latency;
            return new RoleCall(role, true, true, proposal, totalTokens, latency, null);
        } catch (Exception error) {
            long latency = elapsed(started);
            lastStatus = "degraded";
            lastLatencyMs = latency;
            return RoleCall.failure(role, latency, error.getClass().getSimpleName());
        }
    }

    private JsonNode parseObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode parsed = mapper.readTree(content.substring(start, end + 1));
            return parsed.isObject() ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
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
            int totalTokens,
            long latencyMs,
            String error
    ) {
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
