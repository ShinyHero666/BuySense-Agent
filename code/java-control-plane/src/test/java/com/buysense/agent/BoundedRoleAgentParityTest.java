package com.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedRoleAgentParityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void searchObservesApprovedHandoffThenPublishesArtifact() {
        ScriptedTransport transport = new ScriptedTransport(
                completion(toolCall("handoff-call", "request_handoff", Map.of(
                        "to", "recommendation",
                        "capability", "recommendation_strategy_and_retrieval",
                        "reason", "search candidates ground recommendation"))),
                completion(toolCall("publish-call", "publish_artifact", Map.of(
                        "payload", "{\"rankedSkuIds\":[\"sku-1\"]}"))));
        List<String> events = new ArrayList<>();
        BoundedCollaborationCoordinator.TraceSink trace =
                (role, event, detail) -> events.add(role + ":" + event);
        BoundedCollaborationCoordinator coordinator =
                new BoundedCollaborationCoordinator("role-run", trace);
        ArtifactStore artifacts = new ArtifactStore("role-run");
        BoundedRoleAgent role = new BoundedRoleAgent(
                transport, mapper, artifacts, coordinator, trace);

        ObjectNode input = mapper.createObjectNode();
        input.putArray("rankedSkuIds").add("sku-1");
        BoundedRoleAgent.Result result = role.run(new BoundedRoleAgent.Spec(
                "search", "role-run", "search-task", "candidate_set",
                AgentArtifact.Status.VERIFIED, input, null,
                "{\"rankedSkuIds\":[\"sku-1\"]}",
                "You are the Search Agent.",
                (authoritative, proposal) -> BoundedRoleAgent.Resolution.accepted(proposal),
                authoritative -> authoritative));

        assertThat(result.outcome()).isEqualTo(BoundedRoleAgent.Outcome.ACCEPTED);
        assertThat(result.artifact().payload()).containsEntry("rankedSkuIds", List.of("sku-1"));
        assertThat(transport.requests).hasSize(2);
        AgentModelTransport.Request secondTurn = transport.requests.get(1);
        assertThat(secondTurn.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.toolCallId()).isEqualTo("handoff-call");
            assertThat(message.content()).contains("approved");
        });
        assertThat(coordinator.proposals()).singleElement().satisfies(proposal -> {
            assertThat(proposal.proposedBy()).isEqualTo("search");
            assertThat(proposal.status()).isEqualTo("approved");
        });
        assertThat(events).contains(
                "search:delegation_proposal_reviewed",
                "search:artifact_published");
        assertThat(events).containsSubsequence(
                "search:agent_end", "search:model_execution", "search:artifact_published");
    }

    @Test
    void rejectedHandoffIsReturnedToSameAgentBeforeArtifactPublication() {
        ScriptedTransport transport = new ScriptedTransport(
                completion(toolCall("bad-handoff", "request_handoff", Map.of(
                        "to", "pricing",
                        "capability", "live_quote_tool",
                        "reason", "denied cross-role request"))),
                completion(toolCall("publish-after-rejection", "publish_artifact", Map.of(
                        "payload", "{\"rankedSkuIds\":[\"sku-1\"]}"))));
        BoundedCollaborationCoordinator.TraceSink trace = (role, event, detail) -> { };
        BoundedCollaborationCoordinator coordinator =
                new BoundedCollaborationCoordinator("rejected-run", trace);
        BoundedRoleAgent role = new BoundedRoleAgent(
                transport, mapper, new ArtifactStore("rejected-run"), coordinator, trace);
        ObjectNode input = mapper.createObjectNode();
        input.putArray("rankedSkuIds").add("sku-1");

        BoundedRoleAgent.Result result = role.run(new BoundedRoleAgent.Spec(
                "search", "rejected-run", "search-task", "candidate_set",
                AgentArtifact.Status.VERIFIED, input, null, "{}", "Search Agent",
                (authoritative, proposal) -> BoundedRoleAgent.Resolution.accepted(proposal),
                authoritative -> authoritative));

        assertThat(result.outcome()).isEqualTo(BoundedRoleAgent.Outcome.ACCEPTED);
        assertThat(transport.requests.get(1).messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.content()).contains("rejected");
            assertThat(message.content()).contains("delegation_edge_denied:search->pricing");
        });
    }

    @Test
    void missingPublicationFallsBackDeterministically() {
        ScriptedTransport transport = new ScriptedTransport(
                new AgentModelTransport.Completion("no tool", List.of(),
                        new AgentModelTransport.Usage(5, 2, 7)));
        BoundedCollaborationCoordinator.TraceSink trace = (role, event, detail) -> { };
        BoundedCollaborationCoordinator coordinator =
                new BoundedCollaborationCoordinator("fallback-run", trace);
        BoundedRoleAgent role = new BoundedRoleAgent(
                transport, mapper, new ArtifactStore("fallback-run"), coordinator, trace);
        ObjectNode input = mapper.createObjectNode().put("source", "deterministic");

        BoundedRoleAgent.Result result = role.run(new BoundedRoleAgent.Spec(
                "search", "fallback-run", "search-task", "candidate_set",
                AgentArtifact.Status.VERIFIED, input, null, "{}", "Search Agent",
                (authoritative, proposal) -> BoundedRoleAgent.Resolution.accepted(proposal),
                authoritative -> authoritative));

        assertThat(result.outcome()).isEqualTo(BoundedRoleAgent.Outcome.FALLBACK);
        assertThat(result.artifact().payload()).containsEntry("source", "deterministic");
        assertThat(result.roleCall().success()).isFalse();
        assertThat(result.roleCall().totalTokens()).isEqualTo(7);
    }

    @Test
    void replayTraversesTheAgentToolLoopWithoutCountingARemoteAttempt() {
        AtomicInteger turns = new AtomicInteger();
        AgentModelTransport replayTransport = new AgentModelTransport() {
            @Override
            public String mode() {
                return "replay";
            }

            @Override
            public Completion complete(Request request) {
                turns.incrementAndGet();
                ObjectNode arguments = mapper.createObjectNode();
                arguments.put("payload", "{\"source\":\"untrusted-replay-proposal\"}");
                return new Completion(
                        null,
                        List.of(new ToolCall(
                                "replay-publish",
                                "publish_artifact",
                                arguments)),
                        Usage.ZERO);
            }
        };
        List<String> events = new ArrayList<>();
        BoundedCollaborationCoordinator.TraceSink trace =
                (role, event, detail) -> events.add(event + ":" + detail.getOrDefault("mode", ""));
        BoundedCollaborationCoordinator coordinator =
                new BoundedCollaborationCoordinator("replay-run", trace);
        BoundedRoleAgent role = new BoundedRoleAgent(
                replayTransport,
                mapper,
                new ArtifactStore("replay-run"),
                coordinator,
                trace);
        ObjectNode input = mapper.createObjectNode().put("source", "replay");

        BoundedRoleAgent.Result result = role.run(new BoundedRoleAgent.Spec(
                "search", "replay-run", "search-task", "candidate_set",
                AgentArtifact.Status.VERIFIED, input, null, "{}", "Search Agent",
                (authoritative, proposal) -> BoundedRoleAgent.Resolution.accepted(proposal),
                authoritative -> authoritative));

        assertThat(turns).hasValue(1);
        assertThat(result.outcome()).isEqualTo(BoundedRoleAgent.Outcome.REPLAY);
        assertThat(result.artifact().payload()).containsEntry("source", "replay");
        assertThat(result.roleCall().attempted()).isFalse();
        assertThat(result.roleCall().success()).isTrue();
        assertThat(events).contains(
                "before_tool_call:",
                "tool_execution_start:",
                "tool_execution_end:",
                "after_tool_call:",
                "model_execution:replay");
        assertThat(events).containsSubsequence(
                "agent_end:", "model_execution:replay", "artifact_published:");
    }

    @Test
    void cancellationAfterModelReturnPreventsArtifactPublicationAndFallback() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AgentModelTransport transport = new AgentModelTransport() {
            @Override
            public Completion complete(Request request) {
                cancelled.set(true);
                return new Completion(
                        null,
                        List.of(toolCall("publish-after-cancel", "publish_artifact", Map.of(
                                "payload", "{\"rankedSkuIds\":[\"sku-1\"]}"))),
                        new Usage(5, 2, 7));
            }
        };
        BoundedCollaborationCoordinator.TraceSink trace = (role, event, detail) -> { };
        BoundedCollaborationCoordinator coordinator =
                new BoundedCollaborationCoordinator(
                        "cancelled-role-run",
                        trace,
                        BoundedCollaborationCoordinator.DEFAULT_POLICY,
                        Map.of("lead", Set.of("search")),
                        Map.of("search", Set.of("search_strategy_and_retrieval")),
                        cancelled::get);
        ArtifactStore artifacts = new ArtifactStore("cancelled-role-run");
        BoundedRoleAgent role = new BoundedRoleAgent(
                transport, mapper, artifacts, coordinator, trace);
        ObjectNode input = mapper.createObjectNode();
        input.putArray("rankedSkuIds").add("sku-1");

        assertThatThrownBy(() -> role.run(new BoundedRoleAgent.Spec(
                "search",
                "cancelled-role-run",
                "search-task",
                "candidate_set",
                AgentArtifact.Status.VERIFIED,
                input,
                null,
                "{}",
                "Search Agent",
                (authoritative, proposal) ->
                        BoundedRoleAgent.Resolution.accepted(proposal),
                authoritative -> authoritative)))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("run cancelled");
        assertThat(artifacts.list()).isEmpty();
    }

    private AgentModelTransport.Completion completion(AgentModelTransport.ToolCall call) {
        return new AgentModelTransport.Completion(
                null, List.of(call), new AgentModelTransport.Usage(10, 3, 13));
    }

    private AgentModelTransport.ToolCall toolCall(
            String id,
            String name,
            Map<String, Object> arguments
    ) {
        return new AgentModelTransport.ToolCall(id, name, mapper.valueToTree(arguments));
    }

    private static final class ScriptedTransport implements AgentModelTransport {
        private final Deque<Completion> script;
        private final List<Request> requests = new ArrayList<>();

        private ScriptedTransport(Completion... script) {
            this.script = new ArrayDeque<>(List.of(script));
        }

        @Override
        public Completion complete(Request request) {
            requests.add(request);
            if (script.isEmpty()) throw new IllegalStateException("script exhausted");
            return script.removeFirst();
        }
    }
}
