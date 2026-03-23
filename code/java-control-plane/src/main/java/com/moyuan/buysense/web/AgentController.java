package com.moyuan.buysense.web;

import com.moyuan.buysense.agent.ModelPortAgentBridge;
import com.moyuan.buysense.agent.QualityService;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.retail.RetailDataGateway;
import com.moyuan.buysense.run.PreferenceStore;
import com.moyuan.buysense.run.RunService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping
public class AgentController {
    private final RunService runs;
    private final SessionIdentity identity;
    private final FrontendContractMapper contractMapper;
    private final QualityService qualityService;
    private final PreferenceStore preferences;
    private final ModelPortAgentBridge modelPort;
    private final DomainPackRegistry domains;
    private final RetailDataGateway retail;

    public AgentController(
            RunService runs,
            SessionIdentity identity,
            FrontendContractMapper contractMapper,
            QualityService qualityService,
            PreferenceStore preferences,
            ModelPortAgentBridge modelPort,
            DomainPackRegistry domains,
            RetailDataGateway retail
    ) {
        this.runs = runs;
        this.identity = identity;
        this.contractMapper = contractMapper;
        this.qualityService = qualityService;
        this.preferences = preferences;
        this.modelPort = modelPort;
        this.domains = domains;
        this.retail = retail;
    }

    @GetMapping({"/health", "/health/live", "/health/ready"})
    public Map<String, Object> health() {
        var modelStatus = modelPort.status();
        return Map.of(
                "status", "up",
                "model", Map.of(
                        "mode", modelStatus.mode(),
                        "model", modelStatus.model(),
                        "status", modelStatus.status(),
                        "latencyMs", modelStatus.latencyMs()),
                "dataPlane", Map.of(
                        "mode", "embedded-java",
                        "status", retail.health().values().stream()
                                .anyMatch(source -> "degraded".equals(source.get("status")))
                                ? "degraded" : "embedded",
                        "latencyMs", 0,
                        "retailSources", retail.health()),
                "agentFramework", "Spring Boot adaptive bounded-agent orchestration",
                "paymentEnabled", false);
    }

    @GetMapping("/api/v2/domain-packs")
    public DomainPackRegistry.RegistryView domainPacks() {
        return domains.view();
    }

    @GetMapping("/api/v2/session")
    public Map<String, Object> session(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = identity.resolve(request, response);
        return Map.of(
                "identityId", "anonymous:" + sessionId,
                "sessionId", sessionId,
                "authenticated", false);
    }

    @PostMapping("/api/v2/runs")
    public ResponseEntity<Map<String, Object>> createRun(
            @Valid @RequestBody CreateRunRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String sessionId = identity.resolve(request, response);
        var domain = domains.require(body.domainPackId());
        RunService.Creation creation = runs.create(
                sessionId,
                idempotencyKey,
                body.message(),
                body.confirmed(),
                domain.packId(),
                domain.workflowId(),
                body.proposalRunId());
        var run = creation.run();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getRunId());
        payload.put("domainPackId", run.getDomainPackId());
        payload.put("workflowId", run.getWorkflowId());
        payload.put("status", run.getStatus());
        payload.put("idempotentReplay", creation.replayed());
        payload.put("eventsUrl", "/api/v2/runs/" + run.getRunId() + "/events");
        payload.put("runUrl", "/api/v2/runs/" + run.getRunId());
        return ResponseEntity.status(creation.replayed() ? 200 : 202).body(Map.copyOf(payload));
    }

    @GetMapping("/api/v2/runs/{runId}")
    public FrontendContractMapper.RunView run(
            @PathVariable String runId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String sessionId = identity.resolve(request, response);
        return contractMapper.run(runs.requireOwned(runId, sessionId));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return runs.metrics();
    }

    @GetMapping(path = "/api/v2/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String sessionId = identity.resolve(request, response);
        try {
            return ResponseEntity.ok(runs.subscribeOwned(runId, sessionId, lastEventId));
        } catch (java.util.NoSuchElementException error) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/api/v2/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable String runId,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String sessionId = identity.resolve(request, response);
        runs.cancelOwned(runId, sessionId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v2/preferences")
    public Preferences preferences(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = identity.resolve(request, response);
        var preference = preferences.find(sessionId);
        return new Preferences(preference.personalizationEnabled(), preference.preferredBrand());
    }

    @PutMapping("/api/v2/preferences")
    public Preferences updatePreferences(
            @RequestBody Preferences body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String sessionId = identity.resolve(request, response);
        Preferences normalized = new Preferences(
                body.personalizationEnabled(),
                body.preferredBrand() == null ? "" : body.preferredBrand());
        preferences.save(sessionId, new PreferenceStore.Preference(
                normalized.personalizationEnabled(), normalized.preferredBrand()));
        return normalized;
    }

    @PostMapping("/api/v2/interactions")
    public ResponseEntity<Void> interaction() {
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/api/v2/interactions")
    public ResponseEntity<Void> clearInteractions() {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v2/quality")
    public QualityService.Report quality() {
        return qualityService.report();
    }

    public record CreateRunRequest(
            @NotBlank @Size(max = 4_000) String message,
            boolean confirmed,
            String domainPackId,
            String proposalRunId
    ) {
        public CreateRunRequest {
            domainPackId = domainPackId == null || domainPackId.isBlank()
                    ? DomainPackRegistry.DEFAULT_PACK_ID
                    : domainPackId;
            proposalRunId = proposalRunId == null || proposalRunId.isBlank()
                    ? null : proposalRunId;
        }
    }

    public record Preferences(boolean personalizationEnabled, String preferredBrand) {
    }
}