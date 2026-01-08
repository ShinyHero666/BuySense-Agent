package com.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPortAgentBridgeToolCallTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsToolsAndParsesNativeToolCalls() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        AtomicReference<String> roleHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(mapper.readTree(exchange.getRequestBody()));
            roleHeader.set(exchange.getRequestHeaders().getFirst("x-modelport-agent-role"));
            ObjectNode response = mapper.createObjectNode();
            ObjectNode message = response.putArray("choices").addObject().putObject("message");
            message.put("role", "assistant");
            message.putNull("content");
            ObjectNode function = message.putArray("tool_calls").addObject()
                    .put("id", "call-1")
                    .put("type", "function")
                    .putObject("function");
            function.put("name", "request_handoff");
            function.put("arguments", mapper.writeValueAsString(Map.of(
                    "to", "recommendation",
                    "capability", "recommendation_strategy_and_retrieval",
                    "reason", "ground candidates")));
            response.putObject("usage")
                    .put("prompt_tokens", 11)
                    .put("completion_tokens", 4)
                    .put("total_tokens", 15);
            send(exchange, mapper.writeValueAsString(response));
        });
        server.start();
        try {
            ModelPortProperties properties = new ModelPortProperties();
            properties.setEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-key");
            properties.setModel("test-model");
            properties.setConnectTimeout(Duration.ofSeconds(1));
            properties.setReadTimeout(Duration.ofSeconds(2));
            ModelPortAgentBridge bridge = new ModelPortAgentBridge(properties, mapper);

            properties.setRoleModels(Map.of("search", "search-model"));
            JsonNode schema = mapper.readTree(
                    "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"}}}");
            AgentModelTransport.Completion completion = bridge.complete(
                    new AgentModelTransport.Request(
                            "run-1",
                            "search",
                            1,
                            List.of(
                                    AgentModelTransport.Message.system("search"),
                                    AgentModelTransport.Message.user("rank candidates")),
                            List.of(new AgentModelTransport.ToolDefinition(
                                    "request_handoff", "request peer work", schema))));

            assertThat(completion.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("call-1");
                assertThat(call.name()).isEqualTo("request_handoff");
                assertThat(call.arguments().path("to").asText()).isEqualTo("recommendation");
            });
            assertThat(completion.usage().totalTokens()).isEqualTo(15);
            assertThat(captured.get().path("thinking").path("type").asText())
                    .isEqualTo("disabled");
            assertThat(captured.get().path("tool_choice").asText()).isEqualTo("required");
            assertThat(captured.get().path("parallel_tool_calls").asBoolean()).isFalse();
            assertThat(captured.get().path("tools").get(0).path("function").path("name").asText())
                    .isEqualTo("request_handoff");
            assertThat(roleHeader.get()).isEqualTo("search");
            assertThat(captured.get().path("model").asText()).isEqualTo("search-model");
            assertThat(bridge.describe("search").model()).isEqualTo("search-model");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void replayReturnsALocalPublishArtifactToolCallWithAuthoritativeContext()
            throws Exception {
        ModelPortProperties properties = new ModelPortProperties();
        properties.setEnabled(false);
        ModelPortAgentBridge bridge = new ModelPortAgentBridge(properties, mapper);
        ObjectNode authoritative = mapper.createObjectNode()
                .put("source", "deterministic-replay");

        AgentModelTransport.Completion completion = bridge.complete(
                new AgentModelTransport.Request(
                        "replay-run",
                        "search",
                        1,
                        List.of(
                                AgentModelTransport.Message.system("search"),
                                AgentModelTransport.Message.user(
                                        mapper.writeValueAsString(Map.of(
                                                "authoritativeContext",
                                                authoritative)))),
                        List.of()));

        assertThat(bridge.mode()).isEqualTo("replay");
        assertThat(completion.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-search");
            assertThat(call.name()).isEqualTo("publish_artifact");
            assertThat(call.arguments().path("payload").asText())
                    .satisfies(payload -> {
                        try {
                            assertThat(mapper.readTree(payload).path("source").asText())
                                    .isEqualTo("deterministic-replay");
                        } catch (IOException error) {
                            throw new AssertionError(error);
                        }
                    });
        });
        assertThat(completion.usage()).isEqualTo(AgentModelTransport.Usage.ZERO);
    }

    private void send(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
