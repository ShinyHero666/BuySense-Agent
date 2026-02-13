package com.moyuan.buysense.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
