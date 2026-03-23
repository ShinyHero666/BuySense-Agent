package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.DecisionResult;
import com.moyuan.buysense.domain.DecisionResult.TraceStep;
import com.moyuan.buysense.domain.Requirement;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.platform.ExtensionRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdaptiveDecisionService {
    private final IntentParser parser;
    private final ExecutionRouter router;
    private final DecisionEngine engine;
    private final ModelPortAgentBridge modelPort;
    private final DomainPackRegistry domains;
    private final ExtensionRegistry extensions;

    public AdaptiveDecisionService(
            IntentParser parser,
            ExecutionRouter router,
            DecisionEngine engine,
            ModelPortAgentBridge modelPort,
            DomainPackRegistry domains,
            ExtensionRegistry extensions
    ) {
        this.parser = parser;
        this.router = router;
        this.engine = engine;
        this.modelPort = modelPort;
        this.domains = domains;
        this.extensions = extensions;
    }

    public Execution decide(String runId, String message) {
        return decide(runId, message, DomainPackRegistry.DEFAULT_PACK_ID, "", null);
    }

    public Execution decide(String runId, String message, ExecutionRouter.Mode forcedMode) {
        return decide(runId, message, DomainPackRegistry.DEFAULT_PACK_ID, "", forcedMode);
    }

    public Execution decide(
            String runId,
            String message,
            String domainPackId,
            String preferredBrand
    ) {
        return decide(runId, message, domainPackId, preferredBrand, null);
    }

    private Execution decide(
            String runId,
            String message,
            String domainPackId,
            String preferredBrand,
            ExecutionRouter.Mode forcedMode
    ) {
        var domain = domains.require(domainPackId);
        var workflow = extensions.requireWorkflow(domain.workflowId());
        if (!workflow.capabilityProfileId().equals(domain.capabilityProfileId())) {
            throw new IllegalStateException("domain capability profile mismatch: " + domain.packId());
        }
        extensions.requireDelegation(workflow.id(), "system", "lead");
        extensions.requireDelegation(workflow.id(), "lead", "intent_router");

        Requirement base = parser.parse(message, domain.packId());
        if (preferredBrand != null && !preferredBrand.isBlank() && base.preferredBrand().isBlank()) {
            base = base.withPreferredBrand(preferredBrand);
        }
        ExecutionRouter.Route route = forcedMode == null
                ? router.route(base)
                : router.force(base, forcedMode);

        ModelPortAgentBridge.RoleCall planner = route.mode() == ExecutionRouter.Mode.HYBRID
                && !route.clarificationRecommended()
                ? modelPort.plan(runId, message)
                : ModelPortAgentBridge.RoleCall.disabled("planner");
        Requirement effective = planner.success()
                ? parser.enrich(base, planner.rewrittenQuery(), domain.packId())
                : base;
        if (route.clarificationRecommended()) {
            ModelPortAgentBridge.RoleCall critic = ModelPortAgentBridge.RoleCall.disabled("critic");
            DecisionResult result = clarificationResult(effective, route.clarificationQuestion())
                    .withRuntime(modelPort.runtime(route, planner, critic, false));
            result = result.appendTrace(orchestrationTrace(
                    route, planner, critic, false, false, domain.packId(), workflow.id()));
            return new Execution(result, route, planner, critic, false);
        }

        validateDataPlaneDelegations(workflow.id(), effective.sponsoredAllowed());
        DecisionResult result = engine.decide(effective, domain.packId());
        ModelPortAgentBridge.RoleCall critic = route.mode() == ExecutionRouter.Mode.HYBRID
                ? modelPort.reviewDecision(runId, message, result)
                : ModelPortAgentBridge.RoleCall.disabled("critic");

        boolean replanned = critic.success()
                && "RETRIEVE".equalsIgnoreCase(critic.verdict())
                && critic.supplementaryQuery() != null
                && !critic.supplementaryQuery().isBlank();
        if (replanned) {
            extensions.requireDelegation(workflow.id(), "critic", "recommendation");
            effective = parser.enrich(effective, critic.supplementaryQuery(), domain.packId());
            result = engine.decide(effective, domain.packId());
        }

        boolean criticClarificationSuggested = critic.success()
                && "CLARIFY".equalsIgnoreCase(critic.verdict())
                && hasText(critic.clarificationQuestion());
        boolean criticClarificationAccepted = criticClarificationSuggested
                && clarificationAllowed(effective, result);
        if (criticClarificationAccepted) {
            extensions.requireDelegation(workflow.id(), "critic", "lead");
            result = clarificationResult(effective, critic.clarificationQuestion());
        }

        result = result.withRuntime(modelPort.runtime(
                route, planner, critic, replanned, criticClarificationAccepted));
        result = result.appendTrace(orchestrationTrace(
                route,
                planner,
                critic,
                replanned,
                criticClarificationAccepted,
                domain.packId(),
                workflow.id()));
        return new Execution(result, route, planner, critic, replanned);
    }

    private void validateDataPlaneDelegations(String workflowId, boolean sponsoredAllowed) {
        extensions.requireDelegation(workflowId, "lead", "search");
        extensions.requireDelegation(workflowId, "lead", "recommendation");
        if (sponsoredAllowed) extensions.requireDelegation(workflowId, "lead", "ads");
        extensions.requireDelegation(workflowId, "lead", "compatibility");
        extensions.requireDelegation(workflowId, "compatibility", "pricing");
        extensions.requireDelegation(workflowId, "compatibility", "review_evidence");
        extensions.requireDelegation(workflowId, "lead", "critic");
    }

    private DecisionResult clarificationResult(Requirement requirement, String question) {
        return new DecisionResult(
                requirement,
                List.of(),
                List.of(),
                List.of(new TraceStep(
                        "policy",
                        "retrieval_blocked_pending_clarification",
                        Map.of("question", question))),
                Map.of(
                        "searchCandidates", 0,
                        "recommendationCandidates", 0,
                        "adCandidates", 0),
                null);
    }

    private boolean clarificationAllowed(Requirement requirement, DecisionResult result) {
        return requirement.requiredCategories().isEmpty()
                || (requirement.bundleRequested() && requirement.budget() == null)
                || result.slate().isEmpty()
                || (requirement.bundleRequested() && result.bundles().isEmpty());
    }

    private TraceStep orchestrationTrace(
            ExecutionRouter.Route route,
            ModelPortAgentBridge.RoleCall planner,
            ModelPortAgentBridge.RoleCall critic,
            boolean replanned,
            boolean criticClarificationAccepted,
            String domainPackId,
            String workflowId
    ) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("domainPackId", domainPackId);
        facts.put("workflowId", workflowId);
        facts.put("mode", route.mode().name().toLowerCase());
        facts.put("reasons", route.reasons());
        facts.put("clarificationRecommended", route.clarificationRecommended());
        facts.put("plannerApplied", planner.success());
        facts.put("criticVerdict", critic.verdict() == null ? "not_called" : critic.verdict());
        facts.put("clarificationRequired",
                route.clarificationRecommended() || criticClarificationAccepted);
        facts.put("criticClarificationAccepted", criticClarificationAccepted);
        facts.put("replanned", replanned);
        facts.put("maxCriticRevisions", 1);
        return new TraceStep("orchestration", "adaptive_bounded_agent_route", Map.copyOf(facts));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Execution(
            DecisionResult result,
            ExecutionRouter.Route route,
            ModelPortAgentBridge.RoleCall planner,
            ModelPortAgentBridge.RoleCall critic,
            boolean replanned
    ) {
        public boolean clarificationRequired() {
            return result.runtime() != null && hasText(result.runtime().clarificationQuestion());
        }

        public String clarificationQuestion() {
            return result.runtime() == null ? null : result.runtime().clarificationQuestion();
        }
    }
}