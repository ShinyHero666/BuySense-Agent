package com.moyuan.buysense.platform;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class ExtensionRegistry {
    public static final String DEFAULT_WORKFLOW_ID = "commerce-decision-v1";
    public static final String DEFAULT_PROFILE_ID = "commerce-bounded-v1";

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$");
    private static final Pattern WORKFLOW_ID = Pattern.compile("^[a-z][a-z0-9-]*-v[0-9]+$");

    private final Map<String, CapabilityDefinition> capabilities;
    private final Map<String, WorkflowDefinition> workflows;

    public ExtensionRegistry() {
        this(defaultCapabilities(), List.of(defaultWorkflow()));
    }

    ExtensionRegistry(
            List<CapabilityDefinition> capabilityDefinitions,
            List<WorkflowDefinition> workflowDefinitions
    ) {
        LinkedHashMap<String, CapabilityDefinition> capabilityMap = new LinkedHashMap<>();
        for (CapabilityDefinition capability : capabilityDefinitions) {
            validateIdentifier(capability.id(), "capability id");
            validateIdentifier(capability.role(), "capability role");
            requireText(capability.version(), "capability version");
            if (capabilityMap.putIfAbsent(capability.id(), capability) != null) {
                throw new IllegalArgumentException("duplicate capability: " + capability.id());
            }
        }
        this.capabilities = Map.copyOf(capabilityMap);

        LinkedHashMap<String, WorkflowDefinition> workflowMap = new LinkedHashMap<>();
        for (WorkflowDefinition workflow : workflowDefinitions) {
            validateWorkflow(workflow);
            if (workflowMap.putIfAbsent(workflow.id(), workflow.freeze()) != null) {
                throw new IllegalArgumentException("duplicate workflow: " + workflow.id());
            }
        }
        this.workflows = Map.copyOf(workflowMap);
    }

    public WorkflowDefinition requireWorkflow(String workflowId) {
        WorkflowDefinition workflow = workflows.get(workflowId);
        if (workflow == null) throw new IllegalArgumentException("unknown_workflow:" + workflowId);
        return workflow;
    }

    public void requireDelegation(String workflowId, String delegatedBy, String targetRole) {
        WorkflowDefinition workflow = requireWorkflow(workflowId);
        if (!workflow.allowedDelegations().getOrDefault(delegatedBy, List.of()).contains(targetRole)) {
            throw new IllegalStateException(
                    "workflow delegation is not allowed: " + delegatedBy + " -> " + targetRole);
        }
    }

    public List<CapabilityDefinition> capabilities() {
        return capabilities.values().stream()
                .sorted(Comparator.comparing(CapabilityDefinition::id))
                .toList();
    }

    private void validateWorkflow(WorkflowDefinition workflow) {
        if (!WORKFLOW_ID.matcher(workflow.id()).matches() || workflow.id().length() > 64) {
            throw new IllegalArgumentException("invalid workflow id: " + workflow.id());
        }
        validateIdentifier(workflow.capabilityProfileId(), "capability profile id");
        requireText(workflow.version(), "workflow version");
        Set<String> uniqueCapabilities = new LinkedHashSet<>(workflow.capabilityIds());
        if (uniqueCapabilities.isEmpty() || uniqueCapabilities.size() != workflow.capabilityIds().size()) {
            throw new IllegalArgumentException("workflow capabilities must be non-empty and unique");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String capabilityId : uniqueCapabilities) {
            CapabilityDefinition capability = capabilities.get(capabilityId);
            if (capability == null) throw new IllegalArgumentException("unknown capability: " + capabilityId);
            roles.add(capability.role());
        }
        List<String> entries = workflow.allowedDelegations().getOrDefault("system", List.of());
        if (entries.isEmpty()) throw new IllegalArgumentException("workflow requires a system entry role");
        workflow.allowedDelegations().forEach((source, targets) -> {
            if (!source.equals("system") && !roles.contains(source)) {
                throw new IllegalArgumentException("delegation source has no capability: " + source);
            }
            if (targets.size() != new LinkedHashSet<>(targets).size()) {
                throw new IllegalArgumentException("duplicate delegation target from: " + source);
            }
            targets.forEach(target -> {
                if (!roles.contains(target)) {
                    throw new IllegalArgumentException("delegation target has no capability: " + target);
                }
            });
        });
    }

    private static void validateIdentifier(String value, String field) {
        if (value == null || value.length() > 120 || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static List<CapabilityDefinition> defaultCapabilities() {
        return List.of(
                new CapabilityDefinition("calibrated_candidate_fusion", "1.0", "lead"),
                new CapabilityDefinition("grounded_response_composition", "1.0", "lead"),
                new CapabilityDefinition("understand_and_route", "1.0", "intent_router"),
                new CapabilityDefinition("search_strategy_and_retrieval", "1.0", "search"),
                new CapabilityDefinition("recommendation_strategy_and_retrieval", "1.0", "recommendation"),
                new CapabilityDefinition("ads_strategy_and_retrieval", "1.0", "ads"),
                new CapabilityDefinition("constraint_bundle_optimization", "1.0", "compatibility"),
                new CapabilityDefinition("live_quote_tool", "1.0", "pricing"),
                new CapabilityDefinition("review_aspect_tool", "1.0", "review_evidence"),
                new CapabilityDefinition("independent_decision_audit", "1.0", "critic")
        );
    }

    private static WorkflowDefinition defaultWorkflow() {
        List<String> capabilityIds = defaultCapabilities().stream()
                .map(CapabilityDefinition::id)
                .toList();
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("system", List.of("lead"));
        graph.put("lead", List.of("intent_router", "search", "recommendation", "ads",
                "compatibility", "pricing", "review_evidence", "critic", "lead"));
        graph.put("intent_router", List.of("search", "recommendation", "ads"));
        graph.put("search", List.of("recommendation"));
        graph.put("recommendation", List.of("compatibility"));
        graph.put("ads", List.of("critic"));
        graph.put("compatibility", List.of("pricing", "review_evidence", "critic"));
        graph.put("pricing", List.of("critic"));
        graph.put("review_evidence", List.of("critic"));
        graph.put("critic", List.of("recommendation", "lead"));
        return new WorkflowDefinition(
                DEFAULT_WORKFLOW_ID,
                "1.0",
                DEFAULT_PROFILE_ID,
                capabilityIds,
                graph);
    }

    public record CapabilityDefinition(String id, String version, String role) {
    }

    public record WorkflowDefinition(
            String id,
            String version,
            String capabilityProfileId,
            List<String> capabilityIds,
            Map<String, List<String>> allowedDelegations
    ) {
        private WorkflowDefinition freeze() {
            Map<String, List<String>> frozenGraph = new LinkedHashMap<>();
            allowedDelegations.forEach((source, targets) ->
                    frozenGraph.put(source, List.copyOf(new ArrayList<>(targets))));
            return new WorkflowDefinition(
                    id, version, capabilityProfileId, List.copyOf(capabilityIds), Map.copyOf(frozenGraph));
        }
    }
}
