package com.buysense.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedCollaborationCoordinatorTest {
    @Test
    void enforcesCoordinatorBudgets() {
        var policy = BoundedCollaborationCoordinator.DEFAULT_POLICY;
        assertThat(policy.maxTasks()).isEqualTo(18);
        assertThat(policy.maxDelegationProposals()).isEqualTo(12);
        assertThat(policy.maxDepth()).isEqualTo(4);
        assertThat(policy.maxConcurrent()).isEqualTo(4);
        assertThat(policy.maxModelCalls()).isEqualTo(6);
        assertThat(policy.maxRevisionAttempts()).isEqualTo(1);
        assertThat(policy.deadlineMs()).isEqualTo(90_000);
    }

    @Test
    void rejectsUnknownEdgesCapabilitiesAndTheSeventhModelCall() {
        List<String> events = new ArrayList<>();
        var coordinator = new BoundedCollaborationCoordinator(
                "run-1", (role, event, detail) -> events.add(role + ":" + event));

        assertThatThrownBy(() -> coordinator.delegate(
                "ads", "pricing", "live_quote_tool", "run-1", 1, 0, () -> "no"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collaboration_delegation_denied");
        assertThatThrownBy(() -> coordinator.delegate(
                "lead", "search", "live_quote_tool", "run-1", 1, 0, () -> "no"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collaboration_capability_denied");

        for (int index = 0; index < 6; index++) coordinator.consumeModelCall("lead");
        assertThatThrownBy(() -> coordinator.consumeModelCall("lead"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model_call_budget_exhausted");
        assertThat(events).contains("lead:model_budget_consumed");
    }

    @Test
    void modelDelegationNeedsAnAllowedEdgeExactCapabilityAndReason() {
        var coordinator = new BoundedCollaborationCoordinator("run-2", (role, event, detail) -> { });

        var allowed = coordinator.proposeDelegation(
                "search", "recommendation", "recommendation_strategy_and_retrieval",
                "search-task", "primary_product_grounding");
        assertThat(allowed.status()).isEqualTo("approved");
        assertThat(coordinator.takeApprovedProposal(
                "search", "recommendation", "recommendation_strategy_and_retrieval").status())
                .isEqualTo("consumed");

        assertThat(coordinator.proposeDelegation(
                "ads", "pricing", "live_quote_tool", "ads-task", "quote").status())
                .isEqualTo("rejected");
        assertThat(coordinator.proposeDelegation(
                "search", "recommendation", "live_quote_tool", "search-task-2", "quote").status())
                .isEqualTo("rejected");
        assertThat(coordinator.proposeDelegation(
                "search", "recommendation", "recommendation_strategy_and_retrieval",
                "search-task-3", "").status())
                .isEqualTo("rejected");
    }

    @Test
    void executesTheInjectedWorkflowGraphInsteadOfTheDefaultGraph() {
        var coordinator = new BoundedCollaborationCoordinator(
                "custom-workflow",
                (role, event, detail) -> { },
                BoundedCollaborationCoordinator.DEFAULT_POLICY,
                Map.of(
                        "lead", Set.of("intent_router"),
                        "intent_router", Set.of()),
                Map.of(
                        "intent_router", Set.of("understand_and_route")));

        assertThat(coordinator.delegate(
                "lead", "intent_router", "understand_and_route",
                "root", 1, 0, () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> coordinator.delegate(
                "lead", "search", "search_strategy_and_retrieval",
                "root", 1, 0, () -> "no"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collaboration_delegation_denied");
    }

    @Test
    void cancellationBeforeTaskCommitPreventsACompletedTask() {
        AtomicBoolean cancelled = new AtomicBoolean();
        var coordinator = new BoundedCollaborationCoordinator(
                "cancelled-workflow",
                (role, event, detail) -> { },
                BoundedCollaborationCoordinator.DEFAULT_POLICY,
                Map.of("lead", Set.of("search")),
                Map.of("search", Set.of("search_strategy_and_retrieval")),
                cancelled::get);

        assertThatThrownBy(() -> coordinator.delegate(
                "lead",
                "search",
                "search_strategy_and_retrieval",
                "root",
                1,
                0,
                () -> {
                    cancelled.set(true);
                    return "must-not-commit";
                }))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("run cancelled");
        assertThat(coordinator.tasks()).singleElement().satisfies(task ->
                assertThat(task.status()).isEqualTo("failed"));
    }
}
