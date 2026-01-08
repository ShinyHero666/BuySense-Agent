package com.buysense.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.retail.ReviewEvidenceGateway;
import com.buysense.retail.ReviewEvidenceProviderProperties;
import com.buysense.sar.data.JavaRetailDataPlane;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SearchAdsRecsLeadServiceRemoteReviewTest {
    @Autowired
    private IntentParser parser;
    @Autowired
    private DomainPackRegistry domains;
    @Autowired
    private JavaRetailDataPlane dataPlane;
    @Autowired
    private ModelPortAgentBridge model;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    @Qualifier("collaborationExecutor")
    private Executor collaborationExecutor;

    @Test
    void usesTheStandaloneReviewProviderInsideTheFullDecisionChain() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/reviews/query", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("authorization"));
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            send(exchange, reviewResponse(request));
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ReviewEvidenceProviderProperties properties =
                    new ReviewEvidenceProviderProperties(
                            true,
                            baseUrl,
                            "/v1/reviews/query",
                            "main-chain-review-key",
                            false,
                            false,
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2),
                            1_048_576);
            ReviewEvidenceGateway gateway =
                    new ReviewEvidenceGateway(mapper, domains, dataPlane, properties);
            SearchAdsRecsLeadService agent = new SearchAdsRecsLeadService(
                    parser,
                    domains,
                    dataPlane,
                    gateway,
                    model,
                    mapper,
                    collaborationExecutor);

            SearchAdsRecsLeadService.Execution execution = agent.decide(
                    "remote-review-main-chain",
                    "recommend a phone",
                    "normal-3c-v1",
                    new SearchAdsRecsLeadService.DiscoveryContext(
                            "remote-review-user",
                            "remote-review-session",
                            false,
                            List.of(),
                            List.of(),
                            List.of()));

            assertThat(authorization.get()).isEqualTo("Bearer main-chain-review-key");
            assertThat(execution.result().metrics())
                    .containsEntry("reviewVersion", "third-party-review-v1")
                    .containsEntry("reviewSource", "remote_provider");
            assertThat(execution.result().runtime().explanation())
                    .contains("third-party-review-v1", "321");
            AgentArtifact artifact = execution.artifacts().stream()
                    .filter(value -> value.type().equals("review_evidence"))
                    .findFirst()
                    .orElseThrow();
            JsonNode artifactPayload = mapper.valueToTree(artifact.payload());
            assertThat(artifactPayload.path("review_snapshot_version").asText())
                    .isEqualTo("third-party-review-v1");
            assertThat(artifactPayload.path("data_source").path("source").asText())
                    .isEqualTo("remote_provider");
            assertThat(artifactPayload.path("data_source").path("provider_id").asText())
                    .startsWith("review-");
            assertThat(artifactPayload.path("products")).hasSize(1);
            assertThat(artifactPayload.path("products").path(0).path("sample_size").asInt())
                    .isEqualTo(321);
        } finally {
            server.stop(0);
        }
    }

    private ObjectNode reviewResponse(JsonNode request) {
        ObjectNode response = mapper.createObjectNode();
        response.put("review_snapshot_version", "third-party-review-v1");
        ArrayNode products = response.putArray("products");
        for (JsonNode id : request.path("product_ids")) {
            ObjectNode product = products.addObject();
            product.put("product_id", id.asText());
            product.put("sample_size", 321);
            ObjectNode aspect = product.putArray("aspects").addObject();
            aspect.put("dimension", "quality");
            aspect.put("aspect", "quality");
            aspect.put("sentiment", 0.88);
            aspect.put("mention_count", 201);
            aspect.put("confidence", 0.92);
            aspect.put("summary", "Independent review evidence is consistently positive.");
        }
        response.putArray("missing_product_ids");
        return response;
    }

    private void send(HttpExchange exchange, JsonNode response) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
