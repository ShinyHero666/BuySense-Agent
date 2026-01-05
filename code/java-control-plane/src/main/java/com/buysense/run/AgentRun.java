package com.buysense.run;

import com.buysense.domain.DecisionResult;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.platform.ExtensionRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentRun {
    private final String runId;
    private final String identityId;
    private final String sessionId;
    private final String message;
    private final boolean confirmationRequested;
    private final String domainPackId;
    private final String workflowId;
    private final String proposalRunId;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final List<RunEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private volatile String status;
    private volatile Instant updatedAt;
    private volatile DecisionResult result;
    private volatile String error;
    private volatile String errorCode;
    private volatile String phase;
    private volatile CartDraftState cartDraft;

    public AgentRun(String runId, String sessionId, String message) {
        this(runId, sessionId, message, false);
    }

    public AgentRun(String runId, String sessionId, String message, boolean confirmationRequested) {
        this(runId, sessionId, message, confirmationRequested,
                DomainPackRegistry.DEFAULT_PACK_ID, ExtensionRegistry.DEFAULT_WORKFLOW_ID, null);
    }

    public AgentRun(
            String runId,
            String sessionId,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId
    ) {
        this(runId, "id_" + sessionId, sessionId, message, confirmationRequested,
                domainPackId, workflowId, proposalRunId, null, Instant.now());
    }

    public AgentRun(
            String runId,
            String identityId,
            String sessionId,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId
    ) {
        this(runId, identityId, sessionId, message, confirmationRequested,
                domainPackId, workflowId, proposalRunId, null, Instant.now());
    }

    public AgentRun(
            String runId,
            String identityId,
            String sessionId,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId,
            String idempotencyKey
    ) {
        this(runId, identityId, sessionId, message, confirmationRequested,
                domainPackId, workflowId, proposalRunId, idempotencyKey, Instant.now());
    }

    private AgentRun(
            String runId,
            String identityId,
            String sessionId,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId,
            String idempotencyKey,
            Instant createdAt
    ) {
        this.runId = runId;
        this.identityId = identityId;
        this.sessionId = sessionId;
        this.message = message;
        this.confirmationRequested = confirmationRequested;
        this.domainPackId = domainPackId;
        this.workflowId = workflowId;
        this.proposalRunId = proposalRunId;
        this.idempotencyKey = idempotencyKey;
        this.status = "queued";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public static AgentRun restore(
            String runId,
            String identityId,
            String sessionId,
            String message,
            boolean confirmationRequested,
            String domainPackId,
            String workflowId,
            String proposalRunId,
            String idempotencyKey,
            String status,
            Instant createdAt,
            Instant updatedAt,
            DecisionResult result,
            String error,
            String errorCode,
            String phase,
            CartDraftState cartDraft,
            boolean cancellationRequested,
            List<RunEvent> events
    ) {
        AgentRun run = new AgentRun(
                runId, identityId, sessionId, message, confirmationRequested,
                domainPackId, workflowId, proposalRunId, idempotencyKey, createdAt);
        run.status = status;
        run.updatedAt = updatedAt;
        run.result = result;
        run.error = error;
        run.errorCode = errorCode;
        run.phase = phase;
        run.cartDraft = cartDraft;
        run.cancellationRequested.set(cancellationRequested);
        run.events.addAll(events);
        return run;
    }

    public String getRunId() { return runId; }
    public String getIdentityId() { return identityId; }
    public String getSessionId() { return sessionId; }
    public String getMessage() { return message; }
    public boolean isConfirmationRequested() { return confirmationRequested; }
    public String getDomainPackId() { return domainPackId; }
    public String getWorkflowId() { return workflowId; }
    public String getProposalRunId() { return proposalRunId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public DecisionResult getResult() { return result; }
    public String getError() { return error; }
    public String getErrorCode() { return errorCode; }
    public String getPhase() { return phase; }
    public CartDraftState getCartDraft() { return cartDraft; }
    public List<RunEvent> getEvents() { return List.copyOf(events); }
    public boolean isCancellationRequested() { return cancellationRequested.get(); }

    public synchronized void transition(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public synchronized void complete(DecisionResult result) {
        this.result = result;
        transition("completed");
    }

    public synchronized void prepareResult(DecisionResult result) {
        prepareResult(result, "proposal");
    }

    public synchronized void prepareResult(DecisionResult result, String phase) {
        this.result = result;
        this.error = null;
        this.errorCode = null;
        this.phase = phase;
        this.updatedAt = Instant.now();
    }

    public synchronized void prepareCartDraft(DecisionResult result, CartDraftState cartDraft) {
        this.result = result;
        this.error = null;
        this.errorCode = null;
        this.cartDraft = cartDraft;
        this.phase = "cart_draft";
        this.updatedAt = Instant.now();
    }

    public synchronized void prepareNoPendingDecision() {
        this.phase = "no_pending_decision";
        this.updatedAt = Instant.now();
    }

    public synchronized void fail(Throwable throwable) {
        this.error = throwable.getMessage();
        this.errorCode = "agent_execution_failed";
        transition("failed");
    }

    public synchronized void prepareFailure(Throwable throwable) {
        this.error = throwable.getMessage();
        this.errorCode = "agent_execution_failed";
        this.updatedAt = Instant.now();
    }

    public synchronized void resetForRetry(Instant retryAt) {
        this.status = "queued";
        this.result = null;
        this.error = null;
        this.errorCode = null;
        this.phase = null;
        this.cartDraft = null;
        this.cancellationRequested.set(false);
        this.updatedAt = retryAt;
    }

    public void cancel() {
        cancellationRequested.set(true);
        errorCode = "run_cancelled";
        transition("cancelled");
    }

    public void requestCancellation() {
        cancellationRequested.set(true);
        updatedAt = Instant.now();
    }

    public void addEvent(RunEvent event) {
        events.add(event);
        updatedAt = Instant.now();
    }

    public record CartDraftState(
            String draftId,
            BigDecimal totalPrice,
            Instant expiresAt,
            boolean paymentAuthorized
    ) {
    }
}
