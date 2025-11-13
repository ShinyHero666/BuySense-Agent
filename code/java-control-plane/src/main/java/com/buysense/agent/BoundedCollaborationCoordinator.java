package com.buysense.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Bounded collaboration task board for BuySense decision runs.
 * The coordinator, rather than an LLM, owns every capability, graph and budget gate.
 */
public final class BoundedCollaborationCoordinator {
    // Six roles plus a bounded allowance for handoff observations and recoverable errors.
    public static final Policy DEFAULT_POLICY = new Policy(18, 12, 4, 4, 12, 1, 90_000);

    private static final Map<String, Set<String>> ALLOWED_DELEGATIONS = Map.ofEntries(
            Map.entry("system", Set.of("lead")),
            Map.entry("lead", Set.of(
                    "intent_router", "search", "recommendation", "ads", "compatibility",
                    "pricing", "review_evidence", "critic", "lead")),
            Map.entry("intent_router", Set.of("search", "recommendation", "ads")),
            Map.entry("search", Set.of("recommendation")),
            Map.entry("recommendation", Set.of("compatibility")),
            Map.entry("ads", Set.of("critic")),
            Map.entry("compatibility", Set.of("pricing", "review_evidence", "critic")),
            Map.entry("pricing", Set.of("critic")),
            Map.entry("review_evidence", Set.of("critic")),
            Map.entry("critic", Set.of("recommendation", "lead")));

    private static final Map<String, Set<String>> ROLE_CAPABILITIES = Map.ofEntries(
            Map.entry("lead", Set.of("calibrated_candidate_fusion", "grounded_response_composition")),
            Map.entry("intent_router", Set.of("understand_and_route")),
            Map.entry("search", Set.of("search_strategy_and_retrieval")),
            Map.entry("recommendation", Set.of("recommendation_strategy_and_retrieval")),
            Map.entry("ads", Set.of("ads_strategy_and_retrieval")),
            Map.entry("compatibility", Set.of("constraint_bundle_optimization")),
            Map.entry("pricing", Set.of("live_quote_tool")),
            Map.entry("review_evidence", Set.of("review_aspect_tool")),
            Map.entry("critic", Set.of("independent_decision_audit")));

    private final String runId;
    private final Policy policy;
    private final TraceSink trace;
    private final long deadlineNanos;
    private final Semaphore slots;
    private final Map<String, Set<String>> allowedDelegations;
    private final Map<String, Set<String>> roleCapabilities;
    private final BooleanSupplier cancellationRequested;
    private final AtomicInteger nextTask = new AtomicInteger(1);
    private final AtomicInteger nextProposal = new AtomicInteger(1);
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<Task> tasks = new ArrayList<>();
    private final List<DelegationProposal> proposals = new ArrayList<>();

    public BoundedCollaborationCoordinator(String runId, TraceSink trace) {
        this(runId, trace, DEFAULT_POLICY);
    }

    public BoundedCollaborationCoordinator(String runId, TraceSink trace, Policy policy) {
        this(runId, trace, policy, ALLOWED_DELEGATIONS, ROLE_CAPABILITIES);
    }

    public BoundedCollaborationCoordinator(
            String runId,
            TraceSink trace,
            Policy policy,
            Map<String, Set<String>> allowedDelegations,
            Map<String, Set<String>> roleCapabilities
    ) {
        this(runId, trace, policy, allowedDelegations, roleCapabilities, () -> false);
    }

    public BoundedCollaborationCoordinator(
            String runId,
            TraceSink trace,
            Policy policy,
            Map<String, Set<String>> allowedDelegations,
            Map<String, Set<String>> roleCapabilities,
            BooleanSupplier cancellationRequested
    ) {
        this.runId = runId;
        this.trace = trace;
        this.policy = policy;
        this.allowedDelegations = immutableGraph(allowedDelegations);
        this.roleCapabilities = immutableGraph(roleCapabilities);
        this.cancellationRequested = cancellationRequested;
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.deadlineMs());
        this.slots = new Semaphore(policy.maxConcurrent(), true);
    }

    private static Map<String, Set<String>> immutableGraph(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((key, values) -> copy.put(key, Set.copyOf(values)));
        return Map.copyOf(copy);
    }

    public int consumeModelCall(String role) {
        assertDeadline();
        int used = modelCalls.incrementAndGet();
        if (used > policy.maxModelCalls()) {
            modelCalls.decrementAndGet();
            throw new IllegalStateException("collaboration_model_call_budget_exhausted");
        }
        trace.add(role, "model_budget_consumed", Map.of("used", used, "limit", policy.maxModelCalls()));
        return used;
    }

    public java.time.Duration remainingTime() {
        assertActive();
        return java.time.Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime()));
    }

    public synchronized DelegationProposal proposeDelegation(
            String proposedBy,
            String role,
            String capability,
            String parentTaskId,
            String reason
    ) {
        assertDeadline();
        DelegationProposal duplicate = proposals.stream()
                .filter(item -> item.proposedBy().equals(proposedBy))
                .filter(item -> item.role().equals(role))
                .filter(item -> item.capability().equals(capability))
                .filter(item -> java.util.Objects.equals(item.parentTaskId(), parentTaskId))
                .filter(item -> !item.status().equals("rejected"))
                .findFirst().orElse(null);
        if (duplicate != null) return duplicate;

        String rejection = null;
        if (proposals.size() >= policy.maxDelegationProposals()) {
            rejection = "delegation_proposal_budget_exhausted";
        } else if (!canDelegate(proposedBy, role)) {
            rejection = "delegation_edge_denied:" + proposedBy + "->" + role;
        } else if (!hasCapability(role, capability)) {
            rejection = "delegation_capability_denied:" + role + ":" + capability;
        } else if (reason == null || reason.isBlank() || reason.length() > 500) {
            rejection = "delegation_reason_invalid";
        }
        DelegationProposal proposal = new DelegationProposal(
                runId + ":proposal-" + String.format("%03d", nextProposal.getAndIncrement()),
                proposedBy,
                role,
                capability,
                parentTaskId,
                reason == null ? "" : reason.trim(),
                rejection == null ? "approved" : "rejected",
                rejection,
                Instant.now());
        proposals.add(proposal);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("proposalId", proposal.proposalId());
        detail.put("to", role);
        detail.put("capability", capability);
        detail.put("status", proposal.status());
        if (rejection != null) detail.put("rejectionReason", rejection);
        trace.add(proposedBy, "delegation_proposal_reviewed", Map.copyOf(detail));
        return proposal;
    }

    public synchronized DelegationProposal takeApprovedProposal(
            String proposedBy,
            String role,
            String capability
    ) {
        for (int index = 0; index < proposals.size(); index++) {
            DelegationProposal proposal = proposals.get(index);
            if (!proposal.status().equals("approved")
                    || !proposal.proposedBy().equals(proposedBy)
                    || !proposal.role().equals(role)
                    || !proposal.capability().equals(capability)) continue;
            DelegationProposal consumed = proposal.withStatus("consumed");
            proposals.set(index, consumed);
            trace.add(proposedBy, "delegation_proposal_consumed", Map.of(
                    "proposalId", proposal.proposalId(), "to", role, "capability", capability));
            return consumed;
        }
        return null;
    }

    public <T> T delegate(
            String delegatedBy,
            String role,
            String capability,
            String parentTaskId,
            int depth,
            int revisionAttempt,
            Callable<T> action
    ) {
        return delegate(
                delegatedBy,
                role,
                capability,
                parentTaskId,
                depth,
                revisionAttempt,
                ignored -> action.call());
    }

    public <T> T delegate(
            String delegatedBy,
            String role,
            String capability,
            String parentTaskId,
            int depth,
            int revisionAttempt,
            TaskAction<T> action
    ) {
        assertDeadline();
        if (!canDelegate(delegatedBy, role)) {
            throw new IllegalStateException("collaboration_delegation_denied:" + delegatedBy + "->" + role);
        }
        if (!hasCapability(role, capability)) {
            throw new IllegalStateException("collaboration_capability_denied:" + role + ":" + capability);
        }
        synchronized (this) {
            if (tasks.size() >= policy.maxTasks()) {
                throw new IllegalStateException("collaboration_task_budget_exhausted");
            }
        }
        if (depth > policy.maxDepth()) throw new IllegalStateException("collaboration_depth_exhausted");
        if (revisionAttempt > policy.maxRevisionAttempts()) {
            throw new IllegalStateException("collaboration_revision_budget_exhausted");
        }
        Instant now = Instant.now();
        Task task = new Task(
                runId + ":task-" + String.format("%03d", nextTask.getAndIncrement()),
                parentTaskId, delegatedBy, role, capability, "queued", depth, revisionAttempt, now, now);
        synchronized (this) {
            tasks.add(task);
        }
        trace.add(delegatedBy.equals("system") ? "lead" : delegatedBy, "task_delegated", Map.of(
                "taskId", task.taskId(), "parentTaskId", nullToEmpty(parentTaskId), "to", role,
                "capability", capability, "revisionAttempt", revisionAttempt));

        boolean acquired = false;
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 || !slots.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("collaboration_deadline_exceeded");
            }
            acquired = true;
            assertDeadline();
            update(task.taskId(), "running");
            trace.add(role, "task_started", Map.of(
                    "taskId", task.taskId(), "capability", capability,
                    "parentTaskId", nullToEmpty(parentTaskId)));
            T value = action.call(task);
            assertDeadline();
            update(task.taskId(), "completed");
            trace.add(role, "task_completed", Map.of("taskId", task.taskId(), "capability", capability));
            return value;
        } catch (RuntimeException error) {
            update(task.taskId(), "failed");
            trace.add(role, "task_failed", Map.of(
                    "taskId", task.taskId(), "capability", capability,
                    "errorType", error.getClass().getSimpleName()));
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            update(task.taskId(), "failed");
            trace.add(role, "task_failed", Map.of(
                    "taskId", task.taskId(), "capability", capability,
                    "errorType", "CancellationException"));
            CancellationException cancelled = new CancellationException("run cancelled");
            cancelled.initCause(error);
            throw cancelled;
        } catch (Exception error) {
            update(task.taskId(), "failed");
            trace.add(role, "task_failed", Map.of(
                    "taskId", task.taskId(), "capability", capability,
                    "errorType", error.getClass().getSimpleName()));
            throw new IllegalStateException(error);
        } finally {
            if (acquired) slots.release();
        }
    }

    public synchronized List<Task> tasks() {
        return List.copyOf(tasks);
    }

    public synchronized List<DelegationProposal> proposals() {
        return List.copyOf(proposals);
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    public Policy policy() {
        return policy;
    }


    /** Allows an in-flight role Agent to enforce the coordinator-owned run deadline. */
    public void assertActive() {
        assertDeadline();
    }
    private synchronized void update(String taskId, String status) {
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            if (task.taskId().equals(taskId)) {
                tasks.set(index, task.withStatus(status));
                return;
            }
        }
    }

    private boolean canDelegate(String from, String to) {
        return allowedDelegations.getOrDefault(from, Set.of()).contains(to);
    }

    private boolean hasCapability(String role, String capability) {
        return roleCapabilities.getOrDefault(role, Set.of()).contains(capability);
    }

    private void assertDeadline() {
        if (Thread.currentThread().isInterrupted() || cancellationRequested.getAsBoolean()) {
            throw new CancellationException("run cancelled");
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException("collaboration_deadline_exceeded");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    public interface TraceSink {
        void add(String role, String event, Map<String, Object> detail);
    }

    @FunctionalInterface
    public interface TaskAction<T> {
        T call(Task task) throws Exception;
    }

    public record Policy(
            int maxTasks,
            int maxDelegationProposals,
            int maxDepth,
            int maxConcurrent,
            int maxModelCalls,
            int maxRevisionAttempts,
            long deadlineMs
    ) {
        public Policy {
            if (maxTasks < 1 || maxDelegationProposals < 1 || maxDepth < 1
                    || maxConcurrent < 1 || maxModelCalls < 1
                    || maxRevisionAttempts < 0 || deadlineMs < 1) {
                throw new IllegalArgumentException("collaboration policy values are invalid");
            }
        }
    }

    public record Task(
            String taskId,
            String parentTaskId,
            String delegatedBy,
            String role,
            String capability,
            String status,
            int depth,
            int revisionAttempt,
            Instant createdAt,
            Instant updatedAt
    ) {
        Task withStatus(String value) {
            return new Task(taskId, parentTaskId, delegatedBy, role, capability, value,
                    depth, revisionAttempt, createdAt, Instant.now());
        }
    }

    public record DelegationProposal(
            String proposalId,
            String proposedBy,
            String role,
            String capability,
            String parentTaskId,
            String reason,
            String status,
            String rejectionReason,
            Instant createdAt
    ) {
        DelegationProposal withStatus(String value) {
            return new DelegationProposal(proposalId, proposedBy, role, capability, parentTaskId,
                    reason, value, rejectionReason, createdAt);
        }
    }
}
