package com.moyuan.buysense.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.agent.ModelPortAgentBridge;
import com.moyuan.buysense.agent.QualityService;
import com.moyuan.buysense.domain.Product;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.run.AgentRun;
import com.moyuan.buysense.run.RunService;
import com.moyuan.buysense.retail.RetailDataGateway;
import com.moyuan.sar.data.JavaRetailDataPlane;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping
public class AgentController {
    private static final Set<String> INTERACTION_TYPES = Set.of(
            "view", "click", "cart", "purchase", "dislike", "ad_impression");

    private final RunService runs;
    private final SessionIdentity identities;
    private final FrontendContractMapper contractMapper;
    private final QualityService qualityService;
    private final ModelPortAgentBridge modelPort;
    private final DomainPackRegistry domains;
    private final JavaRetailDataPlane dataPlane;
    private final RetailDataGateway retail;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentController(
            RunService runs,
            SessionIdentity identities,
            FrontendContractMapper contractMapper,
            QualityService qualityService,
            ModelPortAgentBridge modelPort,
            DomainPackRegistry domains,
            JavaRetailDataPlane dataPlane,
            RetailDataGateway retail,
            JdbcTemplate jdbc,
            ObjectMapper mapper
    ) {
        this.runs = runs;
        this.identities = identities;
        this.contractMapper = contractMapper;
        this.qualityService = qualityService;
        this.modelPort = modelPort;
        this.domains = domains;
        this.dataPlane = dataPlane;
        this.retail = retail;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/health/live")
    public Map<String, Object> live() {
        return Map.of("status", "UP", "service", "moyuan-search-ads-recs-agent");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return runtimeHealth();
    }

    @GetMapping({"/health/ready", "/health/dependencies"})
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> payload = runtimeHealth();
        return ResponseEntity.status(Boolean.TRUE.equals(payload.get("ready")) ? 200 : 503)
                .body(payload);
    }

    @GetMapping("/api/v2/domain-packs")
    public DomainPackRegistry.RegistryView domainPacks() {
        return domains.view();
    }

    @GetMapping("/api/v2/session")
    public SessionIdentity.IdentitySession session(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return identities.resolve(request, response);
    }

    @PostMapping("/api/v2/runs")
    public ResponseEntity<Map<String, Object>> createRun(
            @RequestBody CreateRunRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        identities.assertSameOrigin(request);
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        validateIdempotencyKey(idempotencyKey);
        var domain = domains.require(body.domainPackId());
        RunService.Creation creation = runs.create(
                identity.identityId(),
                identity.sessionId(),
                idempotencyKey,
                body.message(),
                body.confirmed(),
                domain.packId(),
                domain.workflowId(),
                body.proposalRunId());
        AgentRun run = creation.run();
        return ResponseEntity.status(creation.replayed() ? 200 : 202).body(Map.of(
                "runId", run.getRunId(),
                "domainPackId", run.getDomainPackId(),
                "workflowId", run.getWorkflowId(),
                "status", run.getStatus(),
                "eventsUrl", "/api/v2/runs/" + run.getRunId() + "/events",
                "runUrl", "/api/v2/runs/" + run.getRunId(),
                "idempotentReplay", creation.replayed()));
    }

    @GetMapping("/api/v2/runs/{runId}")
    public FrontendContractMapper.RunView run(
            @PathVariable String runId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        return contractMapper.run(runs.requireOwned(runId, identity.identityId()));
    }

    @GetMapping("/api/v2/runs/{runId}/diagnostics")
    public Map<String, Integer> diagnostics(
            @PathVariable String runId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        return runs.diagnosticsOwned(runId, identity.identityId());
    }

    @GetMapping(path = "/api/v2/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "after", required = false) String after,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        try {
            runs.requireOwned(runId, identity.identityId());
        } catch (java.util.NoSuchElementException ignored) {
            return ResponseEntity.notFound().build();
        }
        long cursor = parseCursor(lastEventId != null ? lastEventId : after);
        return ResponseEntity.ok(runs.subscribeOwned(runId, identity.identityId(), cursor));
    }

    @PostMapping("/api/v2/runs/{runId}/cancel")
    public ResponseEntity<FrontendContractMapper.RunView> cancel(
            @PathVariable String runId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        identities.assertSameOrigin(request);
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        AgentRun run = runs.cancelOwned(runId, identity.identityId());
        return ResponseEntity.accepted().body(contractMapper.run(run));
    }

    @GetMapping("/api/v2/cart-drafts/{draftId}")
    public Map<String, Object> cartDraft(
            @PathVariable String draftId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        AgentRun run = runs.requireCartDraftOwned(draftId, identity.sessionId());
        AgentRun.CartDraftState draft = run.getCartDraft();
        List<Product> products = run.getResult().bundles().isEmpty()
                ? List.of(run.getResult().slate().get(0).product())
                : run.getResult().bundles().get(0).items();
        List<Map<String, Object>> items = products.stream().map(product ->
            Map.<String, Object>of(
                    "spuId", product.productId(),
                    "skuId", product.skuId(),
                    "offerId", product.offerId(),
                    "title", product.name(),
                    "quantity", 1,
                    "unitPrice", product.price(),
                    "currency", "CNY",
                    "quoteVersion", product.quoteVersion())).toList();
        return Map.of(
                "draftId", draft.draftId(),
                "sessionId", run.getSessionId(),
                "status", "ready",
                "items", items,
                "totalPrice", draft.totalPrice(),
                "currency", "CNY",
                "quoteBatchId", String.valueOf(
                        run.getResult().metrics().getOrDefault("quoteBatchId", "unknown")),
                "createdAt", run.getUpdatedAt(),
                "expiresAt", draft.expiresAt(),
                "paymentAuthorized", false);
    }

    @GetMapping("/api/v2/preferences")
    public Map<String, Object> preferences(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        Boolean enabled = jdbc.query("""
                        select personalization_enabled from identity_preferences where identity_id = ?
                        """,
                rs -> rs.next() ? rs.getBoolean(1) : null,
                identity.identityId());
        return Map.of(
                "personalizationEnabled", enabled == null || enabled,
                "identityScope", "server_issued_anonymous",
                "retainedInteractionLimit", 100);
    }

    @PutMapping("/api/v2/preferences")
    public Map<String, Object> updatePreferences(
            @RequestBody PreferenceRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        identities.assertSameOrigin(request);
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        jdbc.update("""
                merge into identity_preferences(identity_id, personalization_enabled, updated_at)
                key(identity_id) values (?, ?, ?)
                """, identity.identityId(), body.personalizationEnabled(), Timestamp.from(Instant.now()));
        return preferences(request, response);
    }

    @PostMapping("/api/v2/interactions")
    public ResponseEntity<Map<String, Object>> interaction(
            @RequestBody InteractionRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        identities.assertSameOrigin(request);
        if (!INTERACTION_TYPES.contains(body.eventType())) {
            throw new RequestValidationException("eventType", "unsupported interaction type");
        }
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        jdbc.update("""
                insert into interaction_events(
                    interaction_id, identity_id, session_id, event_type,
                    product_id, payload_json, occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                "interaction_" + UUID.randomUUID(),
                identity.identityId(), identity.sessionId(), body.eventType(),
                body.productId(), write(body.metadata()), Timestamp.from(Instant.now()));
        return ResponseEntity.accepted().body(Map.of("accepted", true));
    }

    @DeleteMapping("/api/v2/interactions")
    public Map<String, Object> clearInteractions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        identities.assertSameOrigin(request);
        SessionIdentity.IdentitySession identity = identities.resolve(request, response);
        int deleted = jdbc.update(
                "delete from interaction_events where identity_id = ?", identity.identityId());
        return Map.of("deleted", deleted, "personalizationHistoryCleared", true);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return runs.metrics();
    }

    @GetMapping("/api/v2/quality")
    public JsonNode quality() {
        return qualityService.report();
    }

    private Map<String, Object> runtimeHealth() {
        boolean storageReady;
        try {
            storageReady = Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class));
        } catch (RuntimeException error) {
            storageReady = false;
        }
        Map<String, Object> embedded = dataPlane.health();
        boolean dataReady = Boolean.TRUE.equals(embedded.get("ready"));
        Map<String, Map<String, Object>> retailSources = retail.health();
        boolean sourceDown = retailSources.values().stream()
                .anyMatch(value -> "down".equals(value.get("status")));
        boolean sourceDegraded = retailSources.values().stream()
                .anyMatch(value -> "degraded".equals(value.get("status"))
                        || Boolean.TRUE.equals(value.get("fallbackActive")));
        String dataStatus = sourceDown ? "down" : sourceDegraded ? "degraded" : "embedded";
        ModelPortAgentBridge.Status model = modelPort.status();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", storageReady && dataReady && !sourceDown ? "up" : "degraded");
        payload.put("ready", storageReady && dataReady && !sourceDown);
        payload.put("storage", Map.of("required", true, "status", storageReady ? "up" : "down"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "java");
        data.put("baseUrl", null);
        data.put("status", dataStatus);
        data.put("latencyMs", 0);
        data.put("error", sourceDown ? "retail_source_unavailable" : null);
        data.put("retailSources", retailSources);
        payload.put("dataPlane", data);
        payload.put("model", Map.of(
                "mode", model.mode(), "model", model.model(),
                "status", model.status(), "latencyMs", model.latencyMs()));
        payload.put("agentFramework", "moyuan-bounded-collaboration-java");
        payload.put("paymentEnabled", false);
        return Map.copyOf(payload);
    }

    private long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new RequestValidationException("after", "must be a non-negative integer");
        }
    }

    private void validateIdempotencyKey(String value) {
        if (value != null && (value.isBlank() || value.length() > 128)) {
            throw new RequestValidationException(
                    "Idempotency-Key", "must contain between 1 and 128 characters");
        }
    }

    private String write(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("metadata is not serializable", error);
        }
    }

    public record CreateRunRequest(
            String message,
            boolean confirmed,
            String domainPackId,
            String proposalRunId
    ) {
        public CreateRunRequest {
            if (message == null || message.isBlank()) {
                throw new RequestValidationException("message", "must be a non-empty string");
            }
            if (message.codePointCount(0, message.length()) > 2_000) {
                throw new RequestValidationException("message", "must contain at most 2000 characters");
            }
            domainPackId = domainPackId == null || domainPackId.isBlank()
                    ? DomainPackRegistry.DEFAULT_PACK_ID : domainPackId;
            proposalRunId = proposalRunId == null || proposalRunId.isBlank()
                    ? null : proposalRunId;
            if (confirmed != (proposalRunId != null)) {
                throw new RequestValidationException(
                        "proposalRunId",
                        confirmed ? "is required for confirmation" : "requires confirmed=true");
            }
        }
    }

    public record PreferenceRequest(boolean personalizationEnabled) {
    }

    public record InteractionRequest(
            String eventType,
            String productId,
            Map<String, Object> metadata
    ) {
        public InteractionRequest {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public static final class RequestValidationException extends RuntimeException {
        private final String field;

        public RequestValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
