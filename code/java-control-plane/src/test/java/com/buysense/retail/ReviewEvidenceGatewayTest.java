package com.buysense.retail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.platform.ExtensionRegistry;
import com.buysense.sar.data.JavaRetailDataPlane;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReviewEvidenceGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainPackRegistry domains =
            new DomainPackRegistry(mapper, new ExtensionRegistry());
    private final JavaRetailDataPlane dataPlane = new JavaRetailDataPlane(mapper);

    @Test
    void callsAStandaloneAuthenticatedReviewProviderAndStampsTrustedProvenance()
            throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/reviews/query", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("authorization"));
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            send(exchange, 200, reviewResponse(request, "third-party-review-v1"));
        });
        server.start();
        try {
            ReviewEvidenceGateway gateway = gateway(server, false);
            ObjectNode response = gateway.reviews(
                    "normal-3c-v1", List.of("spu-iphone-15"));

            assertThat(method.get()).isEqualTo("POST");
            assertThat(path.get()).isEqualTo("/v1/reviews/query");
            assertThat(authorization.get()).isEqualTo("Bearer review-test-key");
            assertThat(response.path("review_snapshot_version").asText())
                    .isEqualTo("third-party-review-v1");
            assertThat(response.path("products").path(0).path("aspects").path(0)
                    .path("dimension").asText()).isEqualTo("photo");
            assertThat(response.path("data_source").path("source").asText())
                    .isEqualTo("remote_provider");
            assertThat(response.path("data_source").path("provider_id").asText())
                    .startsWith("review-")
                    .isEqualTo(response.path("products").path(0).path("provider_id").asText());
            assertThat(response.path("products").path(0).path("source_version").asText())
                    .isEqualTo("third-party-review-v1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToTheVersionedLocalSnapshotAndDisclosesTheReason() throws Exception {
        HttpServer server = failingServer();
        try {
            ObjectNode response = gateway(server, true).reviews(
                    "normal-3c-v1", List.of("spu-iphone-15"));

            assertThat(response.path("review_snapshot_version").asText())
                    .isEqualTo("review-aspects-v2");
            assertThat(response.path("data_source").path("source").asText())
                    .isEqualTo("local_snapshot");
            assertThat(response.path("data_source").path("fallback").asBoolean()).isTrue();
            assertThat(response.path("data_source").path("fallback_reason").asText())
                    .isEqualTo("review_provider_http_error");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClosedWhenRemoteFallbackIsDisabled() throws Exception {
        HttpServer server = failingServer();
        try {
            ReviewEvidenceGateway.ProviderException error = catchThrowableOfType(
                    () -> gateway(server, false).reviews(
                            "normal-3c-v1", List.of("spu-iphone-15")),
                    ReviewEvidenceGateway.ProviderException.class);

            assertThat(error.code()).isEqualTo("review_provider_http_error");
        } finally {
            server.stop(0);
        }
    }

    private ReviewEvidenceGateway gateway(HttpServer server, boolean fallbackEnabled) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ReviewEvidenceProviderProperties properties =
                new ReviewEvidenceProviderProperties(
                        true,
                        baseUrl,
                        "/v1/reviews/query",
                        "review-test-key",
                        fallbackEnabled,
                        false,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        1_048_576);
        return new ReviewEvidenceGateway(mapper, domains, dataPlane, properties);
    }

    private HttpServer failingServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> send(
                exchange, 503, mapper.createObjectNode().put("error", "unavailable")));
        server.start();
        return server;
    }

    private ObjectNode reviewResponse(JsonNode request, String version) {
        ObjectNode response = mapper.createObjectNode();
        response.put("review_snapshot_version", version);
        ArrayNode products = response.putArray("products");
        for (JsonNode id : request.path("product_ids")) {
            ObjectNode product = products.addObject();
            product.put("product_id", id.asText());
            product.put("sample_size", 321);
            ObjectNode aspect = product.putArray("aspects").addObject();
            aspect.put("dimension", "photo");
            aspect.put("aspect", "portrait");
            aspect.put("sentiment", 0.91);
            aspect.put("mention_count", 187);
            aspect.put("confidence", 0.94);
            aspect.put("summary", "Third-party aggregate supports portrait quality.");
        }
        response.putArray("missing_product_ids");
        response.putObject("data_source").put("provider_id", "spoofed-provider");
        return response;
    }

    private void send(HttpExchange exchange, int status, JsonNode response) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
