package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.DecisionResult;
import com.moyuan.buysense.domain.DecisionResult.TraceStep;
import com.moyuan.buysense.domain.Requirement;
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

    public AdaptiveDecisionService(
            IntentParser parser,
            ExecutionRouter router,
            DecisionEngine engine,
            ModelPortAgentBridge modelPort
    ) {
        this.parser = parser;
        this.router = router;
        this.engine = engine;
        this.modelPort = modelPort;
    }

    public Execution decide(String runId, String message) {
        return decide(runId, message, null);
    }

    public Execution decide(String runId, String message, ExecutionRouter.Mode forcedMode) {
        Requirement base = parser.parse(message);
        ExecutionRouter.Route route = forcedMode == null
                ? router.route(base)
                : router.force(base, forcedMode);

        ModelPortAgentBridge.RoleCall planner = route.mode() == ExecutionRouter.Mode.HYBRID
                && !route.clarificationRecommended()
                ? modelPort.plan(runId, message)
                : ModelPortAgentBridge.RoleCall.disabled("planner");
        Requirement effective = planner.success()
                ? parser.enrich(base, planner.rewrittenQuery())
                : base;
        if (route.clarificationRecommended()) {
            ModelPortAgentBridge.RoleCall critic = ModelPortAgentBridge.RoleCall.disabled("critic");
            DecisionResult result = clarificationResult(effective, route.clarificationQuestion())
                    .withRuntime(modelPort.runtime(route, planner, critic, false));
            result = result.appendTrace(orchestrationTrace(route, planner, critic, false, false));
            return new Execution(result, route, planner, critic, false);
        }

        DecisionResult result = engine.decide(effective);
        ModelPortAgentBridge.RoleCall critic = route.mode() == ExecutionRouter.Mode.HYBRID
                ? modelPort.reviewDecision(runId, message, result)
                : ModelPortAgentBridge.RoleCall.disabled("critic");

        boolean replanned = critic.success()
                && "RETRIEVE".equalsIgnoreCase(critic.verdict())
                && critic.supplementaryQuery() != null
                && !critic.supplementaryQuery().isBlank()
                && !route.clarificationRecommended();
        if (replanned) {
            effective = parser.enrich(effective, critic.supplementaryQuery());
            result = engine.decide(effective);
        }

        boolean criticClarificationSuggested = critic.success()
                && "CLARIFY".equalsIgnoreCase(critic.verdict())
                && hasText(critic.clarificationQuestion());
        boolean criticClarificationAccepted = criticClarificationSuggested
                && clarificationAllowed(effective, result);
        if (criticClarificationAccepted) {
            result = clarificationResult(effective, critic.clarificationQuestion());
        }

        result = result.withRuntime(modelPort.runtime(
                route, planner, critic, replanned, criticClarificationAccepted));
        result = result.appendTrace(orchestrationTrace(
                route, planner, critic, replanned, criticClarificationAccepted));

        return new Execution(result, route, planner, critic, replanned);
    }

    private DecisionResult clarificationResult(
            Requirement requirement,
            String question
    ) {
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
            boolean criticClarificationAccepted
    ) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("mode", route.mode().name().toLowerCase());
        facts.put("reasons", route.reasons());
        facts.put("clarificationRecommended", route.clarificationRecommended());
        facts.put("plannerApplied", planner.success());
        String criticVerdict = critic.verdict() == null ? "not_called" : critic.verdict();
        facts.put("criticVerdict", criticVerdict);
        facts.put("clarificationRequired", route.clarificationRecommended()
                || criticClarificationAccepted);
        facts.put("criticClarificationAccepted", criticClarificationAccepted);
        facts.put("replanned", replanned);
        return new TraceStep(
                "orchestration",
                "adaptive_workflow_agent_route",
                Map.copyOf(facts));
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
            return result.runtime() != null
                    && hasText(result.runtime().clarificationQuestion());
        }

        public String clarificationQuestion() {
            return result.runtime() == null
                    ? null
                    : result.runtime().clarificationQuestion();
        }
    }
}
