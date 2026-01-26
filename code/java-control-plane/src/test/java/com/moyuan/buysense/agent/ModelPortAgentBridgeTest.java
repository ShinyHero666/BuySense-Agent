package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.catalog.CatalogRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPortAgentBridgeTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> idempotencyKeys = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void callsTheRealGatewayContractAndAggregatesTwoBoundedRoles() throws Exception {
        startServer();
        ModelPortProperties properties = properties(2);
        ModelPortAgentBridge bridge = new ModelPortAgentBridge(properties, objectMapper);
        var decision = decision();

        var intent = bridge.analyzeIntent("run-1", "预算7000元，配一套拍照设备");
        var explanation = bridge.explainDecision("run-1", "预算7000元，配一套拍照设备", decision);
        var runtime = bridge.runtime(intent, explanation);

        assertThat(requests.get()).isEqualTo(2);
        assertThat(idempotencyKeys).containsExactly(
                "run-1-planner-1",
                "run-1-critic-2");
        assertThat(intent.success()).isTrue();
        assertThat(intent.rewrittenQuery()).contains("预算7000元");
        assertThat(runtime.modelCalls()).isEqualTo(2);
        assertThat(runtime.fallbackCount()).isZero();
        assertThat(runtime.totalTokens()).isEqualTo(30);
        assertThat(runtime.explanation()).isEqualTo("三件套满足预算与兼容约束");
        assertThat(bridge.status().status()).isEqualTo("up");
    }

    @Test
    void neverMakesASecondRequestWhenThePerRunBudgetIsOneCall() throws Exception {
        startServer();
        ModelPortAgentBridge bridge = new ModelPortAgentBridge(properties(1), objectMapper);

        var intent = bridge.analyzeIntent("run-2", "推荐拍照手机");
        var explanation = bridge.explainDecision("run-2", "推荐拍照手机", decision());
        var runtime = bridge.runtime(intent, explanation);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(intent.attempted()).isTrue();
        assertThat(explanation.attempted()).isFalse();
        assertThat(runtime.modelCalls()).isEqualTo(1);
    }

    private ModelPortProperties properties(int maxCalls) {
        ModelPortProperties properties = new ModelPortProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("bridge-test-key");
        properties.setModel("qwen-default");
        properties.setMaxCallsPerRun(maxCalls);
        return properties;
    }

    private com.moyuan.buysense.domain.DecisionResult decision() throws Exception {
        IntentParser parser = new IntentParser(
                new DomainProperties(List.of("phone", "headphones", "charger")));
        DecisionEngine engine = new DecisionEngine(new CatalogRepository(objectMapper));
        return engine.decide(parser.parse("预算7000元，配一套拍照设备"));
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    private void respond(HttpExchange exchange) throws IOException {
        int call = requests.incrementAndGet();
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestHeaders().getFirst("x-api-key")).isEqualTo("bridge-test-key");
        idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        var request = objectMapper.readTree(exchange.getRequestBody());
        assertThat(request.path("model").asText()).isEqualTo("qwen-default");
        assertThat(request.path("messages").isArray()).isTrue();

        String modelContent = call == 1
                ? """
                  {"rewrittenQuery":"预算7000元，购买拍照手机、耳机和充电器",
                   "intent":"bundle_recommendation","explanation":"已保留显式约束"}
                  """
                : """
                  {"explanation":"三件套满足预算与兼容约束"}
                  """;
        var response = objectMapper.createObjectNode();
        response.put("id", "chatcmpl-" + call);
        response.put("model", "qwen-local");
        var message = response.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.put("content", modelContent);
        response.putObject("usage")
                .put("prompt_tokens", 10)
                .put("completion_tokens", 5)
                .put("total_tokens", 15);
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
