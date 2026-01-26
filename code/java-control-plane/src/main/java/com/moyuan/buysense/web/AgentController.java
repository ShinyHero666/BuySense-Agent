package com.moyuan.buysense.web;

import com.moyuan.buysense.agent.QualityService;
import com.moyuan.buysense.agent.ModelPortAgentBridge;
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

    public AgentController(
            RunService runs,
            SessionIdentity identity,
            FrontendContractMapper contractMapper,
            QualityService qualityService,
            PreferenceStore preferences,
            ModelPortAgentBridge modelPort
    ) {
        this.runs = runs;
        this.identity = identity;
        this.contractMapper = contractMapper;
        this.qualityService = qualityService;
        this.preferences = preferences;
        this.modelPort = modelPort;
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
                        "status", "embedded",
                        "latencyMs", 0),
                "agentFramework", "Spring Boot agent orchestration",
                "paymentEnabled", false);
    }

    @GetMapping("/api/v2/session")
    public Map<String, Object> session(HttpServletRequest request, HttpServletResponse response) {
        return Map.of("sessionId", identity.resolve(request, response), "authenticated", false);
    }

    @PostMapping("/api/v2/runs")
    public ResponseEntity<Map<String, Object>> createRun(
            @Valid @RequestBody CreateRunRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String sessionId = identity.resolve(request, response);
        RunService.Creation creation = runs.create(
                sessionId, idempotencyKey, body.message(), body.confirmed());
        var run = creation.run();
        return ResponseEntity.status(creation.replayed() ? 200 : 202).body(Map.of(
                "runId", run.getRunId(),
                "status", run.getStatus(),
                "replayed", creation.replayed(),
                "eventsUrl", "/api/v2/runs/" + run.getRunId() + "/events",
                "runUrl", "/api/v2/runs/" + run.getRunId()
        ));
    }

    @GetMapping("/api/v2/runs/{runId}")
    public FrontendContractMapper.RunView run(@PathVariable String runId) {
        return contractMapper.run(runs.require(runId));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return runs.metrics();
    }

    @GetMapping(path = "/api/v2/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId
    ) {
        return runs.subscribe(runId, lastEventId);
    }

    @PostMapping("/api/v2/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String runId) {
        runs.cancel(runId);
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
        preferences.save(sessionId, new PreferenceStore.Preference(
                body.personalizationEnabled(), body.preferredBrand()));
        return body;
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
            boolean confirmed
    ) {
    }

    public record Preferences(boolean personalizationEnabled, String preferredBrand) {
    }
}
