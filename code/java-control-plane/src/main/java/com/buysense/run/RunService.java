package com.buysense.run;

import com.buysense.agent.SearchAdsRecsLeadService;
import com.buysense.agent.ModelPortAgentBridge;
import com.buysense.domain.Candidate;
import com.buysense.domain.DecisionResult;
import com.buysense.domain.Product;
import com.buysense.retail.RetailDataGateway;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RunService {
    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final SearchAdsRecsLeadService decisionService;
    private final ModelPortAgentBridge modelPort;
    private final RetailDataGateway retail;
    private final RunRepository repository;
    private final RunExecutionProperties executionProperties;
    private final Executor executor;
    private final String workerId = UUID.randomUUID().toString();
    private final Map<String, RunRepository.Lease> activeLeases = new ConcurrentHashMap<>();
    private final Set<String> submittedRuns = ConcurrentHashMap.newKeySet();

    public RunService(
            SearchAdsRecsLeadService decisionService,
            ModelPortAgentBridge modelPort,
            RetailDataGateway retail,
            RunRepository repository,
            RunExecutionProperties executionProperties,
            @Qualifier("agentExecutor") Executor executor
    ) {
        this.decisionService = decisionService;
        this.modelPort = modelPort;
        this.retail = retail;
        this.repository = repository;
        this.executionProperties = executionProperties;
        this.executor = executor;
    }

    @PostConstruct
    void restoreState() {
        reloadFromStorage();
        runs.values().stream()
                .filter(run -> !isTerminal(run.getStatus()))
                .forEach(run -> {
                    if (run.isCancellationRequested()) {
                        run.cancel();
                        repository.update(run);
                        return;
                    }
                    submit(run, true);
                });
    }

    public synchronized Creation create(
            String sessionId,
            String idempotencyKey,
            String message,
            boolean confirmationRequested
    ) {
        return create(
                sessionId,
                idempotencyKey,
                message,
                confirmationRequested,
                com.buysense.platform.DomainPackRegistry.DEFAULT_PACK_ID,
                com.buysense.platform.ExtensionRegistry.DEFAULT_WORKFLOW_ID,
                null);
    }

    public synchronized Creation create(
            String sessionId,
            String idempotencyKey,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId
    ) {
        return create("id_" + sessionId, sessionId, idempotencyKey, message,
                confirmationRequested, domainPackId, workflowId, proposalRunId);
    }

    public synchronized Creation create(
            String identityId,
            String sessionId,
            String idempotencyKey,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId
    ) {
        if (confirmationRequested && (proposalRunId == null || proposalRunId.isBlank())) {
            throw new RunContractException("proposal_run_not_found", "confirmation requires proposalRunId");
        }

        String requestFingerprint = repository.requestFingerprint(
                message, confirmationRequested, domainPackId, workflowId, proposalRunId);
        var existing = repository.findIdempotentRun(identityId, idempotencyKey);
        if (existing.isPresent()) {
            AgentRun replay = requireOwned(existing.get(), identityId);
            if (!repository.idempotencyMatches(identityId, idempotencyKey, requestFingerprint)) {
                throw new RunContractException("idempotency_key_reused", "idempotency key is bound to another request");
            }
            return new Creation(replay, true);
        }

        if (confirmationRequested) {
            var existingConfirmation = repository.findConfirmationByProposal(identityId, proposalRunId);
            if (existingConfirmation.isPresent()) {
                AgentRun replay = requireOwned(existingConfirmation.get(), identityId);
                if (!replay.getDomainPackId().equals(domainPackId)
                        || !replay.getWorkflowId().equals(workflowId)
                        || !replay.getSessionId().equals(sessionId)) {
                    throw new RunContractException(
                            "proposal_extension_mismatch",
                            "proposal extension does not match");
                }
                if (("failed".equals(replay.getStatus()) || "cancelled".equals(replay.getStatus()))
                        && proposalIsRetryable(identityId, domainPackId, workflowId, proposalRunId)) {
                    repository.resetConfirmationForRetry(
                            replay, idempotencyKey, requestFingerprint);
                    emit(replay, "run_created", Map.of(
                            "event", "run_created",
                            "role", "lead",
                            "domainPackId", domainPackId,
                            "workflowId", workflowId,
                            "proposalRunId", proposalRunId,
                            "retry", true,
                            "status", "queued"));
                    submit(replay, false);
                    return new Creation(replay, false);
                }
                repository.addIdempotencyAlias(
                        identityId, idempotencyKey, replay.getRunId(), requestFingerprint);
                return new Creation(replay, true);
            }
            validateProposal(identityId, domainPackId, workflowId, proposalRunId);
        }

        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(),
                identityId,
                sessionId,
                message,
                confirmationRequested,
                domainPackId,
                workflowId,
                proposalRunId,
                idempotencyKey);
        RunEvent createdEvent = new RunEvent(
                UUID.randomUUID().toString(),
                run.getRunId(),
                1,
                "run_created",
                Instant.now(),
                Map.of(
                        "event", "run_created",
                        "role", "lead",
                        "domainPackId", domainPackId,
                        "workflowId", workflowId,
                        "status", "queued"));
        run.addEvent(createdEvent);
        try {
            repository.insertAdmitted(
                    run, idempotencyKey, executionProperties, Instant.now(), createdEvent);
        } catch (RunRepository.AdmissionRejectedException rejected) {
            String reason = switch (rejected.code()) {
                case "identity_concurrency_limit" -> "too many active runs for this identity";
                case "identity_rate_limit" -> "too many runs created during the last minute";
                default -> "global run queue is full";
            };
            throw new RunCapacityException(rejected.code(), reason, rejected.overloaded());
        } catch (DuplicateKeyException race) {
            var idempotentWinner = repository.findIdempotentRun(identityId, idempotencyKey);
            if (idempotentWinner.isPresent()) {
                AgentRun replay = requireOwned(idempotentWinner.get(), identityId);
                if (!repository.idempotencyMatches(
                        identityId, idempotencyKey, requestFingerprint)) {
                    throw new RunContractException(
                            "idempotency_key_reused",
                            "idempotency key is bound to another request");
                }
                return new Creation(replay, true);
            }
            if (confirmationRequested) {
                var confirmationWinner = repository.findConfirmationByProposal(identityId, proposalRunId);
                if (confirmationWinner.isPresent()) {
                    return new Creation(
                            requireOwned(confirmationWinner.get(), identityId),
                            true);
                }
            }
            throw race;
        }
        cache(run);
        publish(run, createdEvent);
        submit(run, false);
        return new Creation(run, false);
    }

    public AgentRun require(String runId) {
        AgentRun run = runs.get(runId);
        if (run == null) {
            run = repository.findById(runId).orElseThrow(
                    () -> new NoSuchElementException("run not found: " + runId));
            cache(run);
        }
        return run;
    }

    public AgentRun requireOwned(String runId, String identityId) {
        AgentRun run = require(runId);
        if (!run.getIdentityId().equals(identityId)) {
            throw new NoSuchElementException("run not found: " + runId);
        }
        return run;
    }

    private AgentRun validateProposal(
            String identityId,
            String domainPackId,
            String workflowId,
            String proposalRunId
    ) {
        AgentRun proposal;
        try {
            proposal = requireOwned(proposalRunId, identityId);
        } catch (NoSuchElementException error) {
            throw new RunContractException("proposal_run_not_found", "proposal run was not found");
        }
        if (proposal.isConfirmationRequested()
                || !proposal.getStatus().equals("completed")
                || proposal.getResult() == null
                || !"proposal".equals(proposal.getPhase())
                || !isQualified(proposal)) {
            throw new RunContractException("proposal_run_not_confirmable", "proposal run is not confirmable");
        }
        if (proposal.getUpdatedAt().plus(executionProperties.proposalTtl()).isBefore(Instant.now())) {
            throw new RunContractException("proposal_expired", "proposal confirmation window expired");
        }
        if (!proposal.getDomainPackId().equals(domainPackId)
                || !proposal.getWorkflowId().equals(workflowId)) {
            throw new RunContractException("proposal_extension_mismatch", "proposal extension does not match");
        }
        return proposal;
    }

    private boolean proposalIsRetryable(
            String identityId,
            String domainPackId,
            String workflowId,
            String proposalRunId
    ) {
        try {
            validateProposal(identityId, domainPackId, workflowId, proposalRunId);
            return true;
        } catch (RunContractException ignored) {
            return false;
        }
    }

    private void submit(AgentRun run, boolean recovering) {
        if (!submittedRuns.add(run.getRunId())) return;
        try {
            executor.execute(() -> {
                try {
                    execute(run);
                } finally {
                    submittedRuns.remove(run.getRunId());
                }
            });
        } catch (TaskRejectedException rejected) {
            submittedRuns.remove(run.getRunId());
            if (recovering) return;
            run.prepareFailure(rejected);
            RunEvent terminalEvent = appendInMemory(run, "run_failed", Map.of(
                    "event", "run_failed",
                    "role", "lead",
                    "status", "failed",
                    "error", "run queue is full"));
            run.transition("failed");
            repository.updateWithEvent(run, terminalEvent);
            publish(run, terminalEvent);
            throw new RunCapacityException("run_queue_full", "global run queue is full", true);
        }
    }

    @Scheduled(fixedDelayString = "${buysense.runs.lease-renewal-delay:PT30S}")
    public void renewActiveLeases() {
        Instant now = Instant.now();
        activeLeases.forEach((runId, lease) -> {
            Instant expiresAt = now.plus(executionProperties.leaseDuration());
            if (repository.renewLease(runId, lease, now, expiresAt)) {
                activeLeases.replace(
                        runId,
                        lease,
                        new RunRepository.Lease(lease.owner(), lease.token(), expiresAt));
            }
        });
    }

    @Scheduled(
            initialDelayString = "${buysense.runs.recovery-initial-delay:PT5S}",
            fixedDelayString = "${buysense.runs.recovery-delay:PT30S}"
    )
    public void recoverExpiredRuns() {
        Instant now = Instant.now();
        int limit = executionProperties.maxConcurrent() + executionProperties.queueCapacity();
        repository.findRecoverableRunIds(now, limit).forEach(runId ->
                repository.findById(runId).ifPresent(run -> {
                    if (!submittedRuns.contains(runId)
                            && !isTerminal(run.getStatus())
                            && !run.isCancellationRequested()) {
                        cache(run);
                        submit(run, true);
                    }
                }));
    }
    @Scheduled(fixedDelayString = "${buysense.runs.cleanup-delay:PT1H}")
    public synchronized void cleanupExpiredRuns() {
        Instant cutoff = Instant.now().minus(executionProperties.terminalRetention());
        repository.deleteTerminalBefore(cutoff).forEach(runId -> {
            runs.remove(runId);
            sequences.remove(runId);
            activeLeases.remove(runId);
            completeSubscribers(runId);
        });
    }
    public synchronized void reloadFromStorage() {
        runs.clear();
        sequences.clear();
        repository.findAll().forEach(this::cache);
    }

    public Map<String, Object> metrics() {
        List<AgentRun> snapshot = List.copyOf(runs.values());
        long denominator = snapshot.stream()
                .filter(run -> !run.isConfirmationRequested())
                .filter(run -> run.getStatus().equals("completed"))
                .count();
        long numerator = snapshot.stream()
                .filter(run -> !run.isConfirmationRequested())
                .filter(run -> run.getStatus().equals("completed"))
                .filter(this::isQualified)
                .count();
        long terminal = snapshot.stream().filter(run -> isTerminal(run.getStatus())).count();
        double qualifiedRate = denominator == 0 ? 0 : numerator / (double) denominator;
        double completionRate = terminal == 0 ? 0
                : snapshot.stream().filter(run -> run.getStatus().equals("completed")).count()
                / (double) terminal;

        return Map.of(
                "northStar", Map.of(
                        "name", "qualifiedDecisionSuccess",
                        "value", qualifiedRate,
                        "numerator", numerator,
                        "denominator", denominator),
                "layers", Map.of(
                        "intent", Map.of("parseSuccessRate", completionRate),
                        "retrieval", Map.of("candidateCoverageRate", qualifiedRate),
                        "ranking", Map.of("slateProductionRate", qualifiedRate),
                        "constraint", Map.of("qualifiedDecisionRate", qualifiedRate),
                        "policy", Map.of("adPolicyPassRate", qualifiedRate),
                        "reliability", Map.of("completionRate", completionRate)),
                "counters", Map.of(
                        "createdRuns", snapshot.size(),
                        "completedRuns", denominator,
                        "failedRuns", snapshot.stream().filter(run -> run.getStatus().equals("failed")).count(),
                        "cancelledRuns", snapshot.stream().filter(run -> run.getStatus().equals("cancelled")).count()));
    }

    public AgentRun cancelOwned(String runId, String identityId) {
        AgentRun run = requireOwned(runId, identityId);
        cancel(run);
        return run;
    }

    public void cancel(String runId) {
        cancel(require(runId));
    }

    private AgentRun cancel(AgentRun run) {
        if (isTerminal(run.getStatus())) return run;
        run.requestCancellation();
        RunEvent event = appendInMemory(run, "run_cancelled", Map.of(
                "event", "run_cancelled",
                "role", "lead",
                "status", "cancelled"));
        run.transition("cancelled");
        repository.updateWithEvent(run, event);
        publish(run, event);
        completeSubscribers(run.getRunId());
        return run;
    }

    public SseEmitter subscribeOwned(String runId, String identityId, long afterSequence) {
        return subscribe(requireOwned(runId, identityId), afterSequence);
    }

    public SseEmitter subscribe(String runId, long afterSequence) {
        return subscribe(require(runId), afterSequence);
    }

    private SseEmitter subscribe(AgentRun run, long afterSequence) {
        String runId = run.getRunId();
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));

        List<RunEvent> firstSnapshot = run.getEvents();
        firstSnapshot.stream().filter(event -> event.sequence() > afterSequence)
                .forEach(event -> send(emitter, event));
        long replayedThrough = firstSnapshot.stream().mapToLong(RunEvent::sequence)
                .max().orElse(afterSequence);
        AgentRun latest = require(runId);
        latest.getEvents().stream().filter(event -> event.sequence() > replayedThrough)
                .forEach(event -> send(emitter, event));
        if (isTerminal(latest.getStatus())) {
            remove(runId, emitter);
            emitter.complete();
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 15_000L)
    public void sendHeartbeats() {
        subscribers.forEach((runId, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException error) {
                remove(runId, emitter);
                emitter.complete();
            }
        }));
    }

    public AgentRun requireCartDraftOwned(String draftId, String sessionId) {
        Instant now = Instant.now();
        AgentRun run = runs.values().stream()
                .filter(candidate -> candidate.getSessionId().equals(sessionId))
                .filter(candidate -> candidate.getCartDraft() != null)
                .filter(candidate -> candidate.getCartDraft().expiresAt().isAfter(now))
                .filter(candidate -> candidate.getCartDraft().draftId().equals(draftId))
                .findFirst().orElse(null);
        if (run != null) return run;
        return repository.findAll().stream()
                .filter(candidate -> candidate.getSessionId().equals(sessionId))
                .filter(candidate -> candidate.getCartDraft() != null)
                .filter(candidate -> candidate.getCartDraft().expiresAt().isAfter(now))
                .filter(candidate -> candidate.getCartDraft().draftId().equals(draftId))
                .findFirst().orElseThrow(() -> new NoSuchElementException("cart draft not found"));
    }

    public Map<String, Integer> diagnosticsOwned(String runId, String identityId) {
        List<RunEvent> events = requireOwned(runId, identityId).getEvents();
        return Map.of(
                "events", events.size(),
                "tasks", (int) events.stream().filter(event -> event.eventType().equals("task")).count(),
                "artifacts", (int) events.stream().filter(event -> event.eventType().equals("artifact")).count(),
                "modelExecutions", (int) events.stream()
                        .filter(event -> event.eventType().equals("model_execution")).count(),
                "constraints", (int) events.stream()
                        .filter(event -> "retrieval_plan".equals(event.payload().get("artifactType"))).count());
    }

    private void execute(AgentRun run) {
        Instant leaseStarted = Instant.now();
        RunRepository.Lease lease = repository.acquireLease(
                        run.getRunId(),
                        workerId,
                        leaseStarted,
                        leaseStarted.plus(executionProperties.leaseDuration()))
                .orElse(null);
        if (lease == null) return;
        activeLeases.put(run.getRunId(), lease);
        run.transition("running");

        try {
            if (run.isCancellationRequested()) return;
            emit(run, "run_started", Map.of(
                    "event", "task_started",
                    "role", "lead",
                    "status", "running"));
            if (run.isConfirmationRequested()) {
                executeConfirmation(run);
                return;
            }
            RunRepository.InteractionContext persistedContext = repository.discoveryContext(
                    run.getIdentityId());
            SearchAdsRecsLeadService.DiscoveryContext discoveryContext =
                    new SearchAdsRecsLeadService.DiscoveryContext(
                            run.getIdentityId(),
                            run.getSessionId(),
                            persistedContext.personalizationEnabled(),
                            persistedContext.recentProductIds(),
                            persistedContext.excludedProductIds(),
                            persistedContext.adExposureProductIds());
            SearchAdsRecsLeadService.Execution execution = decisionService.decide(
                    run.getRunId(), run.getMessage(), run.getDomainPackId(), discoveryContext);
            DecisionResult result = execution.result();
            var requirement = result.requirement();

            emit(run, "artifact", Map.of(
                    "event", "artifact_published",
                    "role", "intent_router",
                    "artifactType", "retrieval_plan",
                    "domainPackId", run.getDomainPackId(),
                    "workflowId", run.getWorkflowId(),
                    "requiredCategories", requirement.requiredCategories(),
                    "constraintCount", requirement.constraints().size()));

            execution.tasks().forEach(task -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("event", "task_completed");
                payload.put("role", task.role());
                payload.put("to", task.role());
                payload.put("taskId", task.taskId());
                if (task.parentTaskId() != null) payload.put("parentTaskId", task.parentTaskId());
                payload.put("capability", task.capability());
                payload.put("status", task.status());
                payload.put("revisionAttempt", task.revisionAttempt());
                emit(run, "task", Map.copyOf(payload));
            });
            execution.proposals().forEach(proposal -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("event", "delegation_proposal_reviewed");
                payload.put("role", proposal.proposedBy());
                payload.put("to", proposal.role());
                payload.put("capability", proposal.capability());
                payload.put("proposalId", proposal.proposalId());
                payload.put("status", proposal.status());
                if (proposal.rejectionReason() != null) {
                    payload.put("rejectionReason", proposal.rejectionReason());
                }
                emit(run, "policy_gate", Map.copyOf(payload));
            });
            execution.roleCalls().forEach(call -> emitModelCall(run, call));

            emitChannelResult(run, result, "search", "searchCandidates");
            emitChannelResult(run, result, "recommendation", "recommendationCandidates");
            if (requirement.sponsoredAllowed()) {
                emitChannelResult(run, result, "ads", "adCandidates");
            }
            emit(run, "artifact", Map.of(
                    "event", "artifact_published",
                    "role", "compatibility",
                    "artifactType", "decision_slate",
                    "slateSize", result.slate().size(),
                    "bundleCount", result.bundles().size()));

            boolean approved = "approved".equals(result.metrics().get("criticVerdict"))
                    && qualified(result);
            emit(run, "policy_gate", Map.of(
                    "event", "task_completed",
                    "role", "critic",
                    "gate", "deterministic_audit",
                    "approved", approved,
                    "violations", result.metrics().getOrDefault("criticViolations", List.of()),
                    "revisionApplied", execution.revisionApplied()));
            if (run.isCancellationRequested()) return;
            run.prepareResult(result, approved ? "proposal" : "needs_replan");
            complete(run, approved ? "proposal" : "needs_replan");
        } catch (RunRepository.LeaseLostException ignored) {
            return;
        } catch (Throwable throwable) {
            run.prepareFailure(throwable);
            RunEvent terminalEvent = appendInMemory(run, "run_failed", Map.of(
                    "event", "run_failed",
                    "role", "lead",
                    "status", "failed",
                    "errorType", throwable.getClass().getSimpleName()));
            run.transition("failed");
            try {
                repository.updateWithEventFenced(run, terminalEvent, lease);
                publish(run, terminalEvent);
            } catch (RunRepository.LeaseLostException ignored) {
                return;
            }
        } finally {
            activeLeases.remove(run.getRunId(), lease);
            completeSubscribers(run.getRunId());
        }
    }

    private void emit(AgentRun run, String type, Map<String, Object> data) {
        RunEvent event = appendInMemory(run, type, data);
        RunRepository.Lease lease = activeLeases.get(run.getRunId());
        if (lease == null) {
            repository.appendEvent(run.getRunId(), event, run.getUpdatedAt());
        } else {
            repository.appendEventFenced(run.getRunId(), event, run.getUpdatedAt(), lease);
        }
        publish(run, event);
    }

    private void executeConfirmation(AgentRun run) {
        emit(run, "task", Map.of(
                "event", "task_delegated",
                "role", "lead",
                "to", "cart",
                "taskId", "cart_draft",
                "proposalRunId", run.getProposalRunId()));
        AgentRun proposal = validateProposal(
                run.getIdentityId(),
                run.getDomainPackId(),
                run.getWorkflowId(),
                run.getProposalRunId());

        DecisionResult proposalResult = proposal.getResult();
        List<Product> proposedItems = proposalResult.bundles().isEmpty()
                ? List.of(proposalResult.slate().get(0).product())
                : proposalResult.bundles().get(0).items();
        RetailDataGateway.RevalidatedSelection current = retail.revalidateSelection(
                run.getDomainPackId(), proposedItems);
        if (proposalResult.requirement().budget() != null
                && current.totalPrice().compareTo(proposalResult.requirement().budget()) > 0) {
            throw new RunContractException(
                    "proposal_budget_changed",
                    "current quote exceeds the confirmed proposal budget");
        }

        DecisionResult refreshedResult = refreshConfirmedDecision(proposalResult, current);
        AgentRun.CartDraftState draft = new AgentRun.CartDraftState(
                UUID.randomUUID().toString(),
                current.totalPrice(),
                Instant.now().plus(executionProperties.proposalTtl()),
                false);
        run.prepareCartDraft(refreshedResult, draft);
        emit(run, "artifact", Map.of(
                "event", "artifact_published",
                "role", "pricing",
                "artifactType", "confirmation_revalidation",
                "proposalRunId", proposal.getRunId(),
                "catalogVersion", current.catalogVersion(),
                "pricingVersion", current.pricingVersion(),
                "providerId", current.providerId(),
                "totalPrice", current.totalPrice()));
        emit(run, "artifact", Map.of(
                "event", "artifact_published",
                "role", "cart",
                "artifactType", "cart_draft",
                "proposalRunId", proposal.getRunId(),
                "draftId", draft.draftId(),
                "totalPrice", draft.totalPrice(),
                "paymentAuthorized", false));
        emit(run, "policy_gate", Map.of(
                "event", "task_completed",
                "role", "cart",
                "gate", "payment_boundary",
                "approved", true,
                "paymentAuthorized", false));
        complete(run, "cart_draft");
    }

    private DecisionResult refreshConfirmedDecision(
            DecisionResult original,
            RetailDataGateway.RevalidatedSelection current
    ) {
        Map<String, Product> refreshedById = new LinkedHashMap<>();
        current.items().forEach(product -> refreshedById.put(product.id(), product));

        List<Candidate> refreshedSlate = original.slate().stream()
                .map(candidate -> {
                    Product refreshed = refreshedById.get(candidate.product().id());
                    if (refreshed == null) return candidate;
                    return new Candidate(
                            refreshed,
                            candidate.score(),
                            candidate.sponsored(),
                            candidate.channels(),
                            candidate.channelScores(),
                            candidate.reasons());
                })
                .toList();

        List<DecisionResult.BundleProposal> refreshedBundles = new ArrayList<>(original.bundles());
        if (!refreshedBundles.isEmpty()) {
            DecisionResult.BundleProposal selected = refreshedBundles.get(0);
            List<String> evidence = new ArrayList<>(selected.evidence());
            evidence.add(current.catalogVersion());
            evidence.add(current.pricingVersion());
            boolean budgetSatisfied = original.requirement().budget() == null
                    || current.totalPrice().compareTo(original.requirement().budget()) <= 0;
            refreshedBundles.set(0, new DecisionResult.BundleProposal(
                    selected.id(),
                    current.items(),
                    current.totalPrice(),
                    selected.score(),
                    budgetSatisfied,
                    true,
                    List.copyOf(evidence)));
        }

        DecisionResult refreshed = new DecisionResult(
                original.requirement(),
                refreshedSlate,
                List.copyOf(refreshedBundles),
                original.trace(),
                original.metrics(),
                original.runtime());
        return refreshed.appendTrace(new DecisionResult.TraceStep(
                "confirmation",
                "live_price_and_stock_revalidated",
                Map.of(
                        "catalogVersion", current.catalogVersion(),
                        "pricingVersion", current.pricingVersion(),
                        "providerId", current.providerId(),
                        "totalPrice", current.totalPrice())));
    }
    private void complete(AgentRun run, String phase) {
        RunEvent terminalEvent = appendInMemory(run, "result", Map.of(
                "event", "run_completed",
                "role", "lead",
                "phase", phase,
                "status", "completed"));
        run.transition("completed");
        RunRepository.Lease lease = activeLeases.get(run.getRunId());
        if (lease == null) {
            repository.updateWithEvent(run, terminalEvent);
        } else {
            repository.updateWithEventFenced(run, terminalEvent, lease);
        }
        publish(run, terminalEvent);
    }

    private RunEvent appendInMemory(AgentRun run, String type, Map<String, Object> data) {
        long sequence = sequences.computeIfAbsent(run.getRunId(), ignored -> new AtomicLong()).incrementAndGet();
        RunEvent event = new RunEvent(UUID.randomUUID().toString(), run.getRunId(), sequence, type, Instant.now(), data);
        run.addEvent(event);
        return event;
    }

    private void publish(AgentRun run, RunEvent event) {
        subscribers.getOrDefault(run.getRunId(), new CopyOnWriteArrayList<>())
                .forEach(emitter -> send(emitter, event));
    }

    private void send(SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.eventType())
                    .data(event));
        } catch (IOException | IllegalStateException error) {
            emitter.complete();
        }
    }

    private void completeSubscribers(String runId) {
        var emitters = subscribers.remove(runId);
        if (emitters != null) emitters.forEach(SseEmitter::complete);
    }

    private void remove(String runId, SseEmitter emitter) {
        var emitters = subscribers.get(runId);
        if (emitters != null) emitters.remove(emitter);
    }

    private static boolean isTerminal(String status) {
        return status.equals("completed") || status.equals("failed") || status.equals("cancelled");
    }

    private boolean isQualified(AgentRun run) {
        return run.getResult() != null && qualified(run.getResult());
    }

    private boolean qualified(com.buysense.domain.DecisionResult result) {
        if (result.slate().isEmpty()) return false;
        if (result.requirement().bundleRequested() && result.bundles().isEmpty()) return false;
        if (!result.requirement().sponsoredAllowed()
                && result.slate().stream().anyMatch(com.buysense.domain.Candidate::sponsored)) {
            return false;
        }
        if (result.bundles().stream()
                .anyMatch(bundle -> !bundle.budgetSatisfied() || !bundle.compatible())) {
            return false;
        }
        return result.slate().stream().limit(3)
                .filter(com.buysense.domain.Candidate::sponsored)
                .count() <= 1;
    }

    private void emitChannelResult(AgentRun run, com.buysense.domain.DecisionResult result,
                                   String channel, String metricKey) {
        Object value = result.metrics().getOrDefault(metricKey, 0);
        int candidateCount = value instanceof Number number ? number.intValue() : 0;
        emit(run, "task", Map.of(
                "event", "data_plane_result",
                "role", channel,
                "channel", channel,
                "candidateCount", candidateCount));
    }

    private void emitModelCall(AgentRun run, ModelPortAgentBridge.RoleCall call) {
        if (!call.attempted()) return;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", "model_budget_consumed");
        payload.put("role", call.role());
        payload.put("model", modelPort.status().model());
        payload.put("success", call.success());
        payload.put("totalTokens", call.totalTokens());
        payload.put("latencyMs", call.latencyMs());
        payload.put("fallback", !call.success());
        if (call.error() != null) payload.put("error", call.error());
        emit(run, "model_execution", Map.copyOf(payload));
    }

    private void cache(AgentRun run) {
        runs.put(run.getRunId(), run);
        long maxSequence = run.getEvents().stream().mapToLong(RunEvent::sequence).max().orElse(0);
        sequences.put(run.getRunId(), new AtomicLong(maxSequence));
    }

    public static final class RunCapacityException extends RuntimeException {
        private final String code;
        private final boolean overloaded;

        public RunCapacityException(String code, String message, boolean overloaded) {
            super(message);
            this.code = code;
            this.overloaded = overloaded;
        }

        public String code() {
            return code;
        }

        public boolean overloaded() {
            return overloaded;
        }
    }
    public static final class RunContractException extends RuntimeException {
        private final String code;

        public RunContractException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
    public record Creation(AgentRun run, boolean replayed) {
    }
}
