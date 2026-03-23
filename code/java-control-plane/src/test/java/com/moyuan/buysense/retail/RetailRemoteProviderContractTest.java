package com.moyuan.buysense.retail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.platform.ExtensionRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetailRemoteProviderContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainPackRegistry domains = new DomainPackRegistry(mapper, new ExtensionRegistry());

    @Test
    void loadsCatalogReviewsAndQuotesFromAnAuthenticatedRemoteProvider() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String providerId = providerId(baseUrl);
        server.createContext("/", exchange -> serveRemote(exchange, providerId, requests, "none"));
        server.start();
        try {
            RetailDataGateway gateway = gateway(baseUrl, false);
            RetailDataSnapshot snapshot = gateway.load("normal-3c-v1");

            assertThat(snapshot.products()).isNotEmpty()
                    .allSatisfy(product -> {
                        assertThat(product.source()).isEqualTo("remote_provider");
                        assertThat(product.providerId()).isEqualTo(providerId);
                    });
            assertThat(snapshot.evidenceByProduct()).containsKey("spu-iphone-15");
            assertThat(requests).hasSize(3)
                    .allSatisfy(request -> assertThat(request).endsWith("Bearer test-api-key"));
            assertThat(requests).anyMatch(request -> request.startsWith("GET /v1/catalog/normal-3c-v1"))
                    .anyMatch(request -> request.startsWith("POST /v1/reviews/query"))
                    .anyMatch(request -> request.startsWith("POST /v1/prices/quote"));
            gateway.health().values().forEach(source ->
                    assertThat(source).containsEntry("effectiveSource", "remote_provider")
                            .containsEntry("status", "up")
                            .containsEntry("fallbackActive", false)
                            .containsEntry("providerId", providerId));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsDuplicateJsonKeysBeforeTrustingProviderMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> sendRaw(exchange, 200,
                "{\"catalog_version\":\"one\",\"catalog_version\":\"two\"}"));
        server.start();
        try {
            RetailDataGateway gateway = gateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(), false);

            assertThatThrownBy(() -> gateway.load("normal-3c-v1"))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("provider_invalid_response");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsDuplicateCatalogOfferIds() throws Exception {
        assertMutatedProviderIsRejected("duplicate_offer");
    }

    @Test
    void rejectsPricingResponsesThatSwapInAnUnrequestedOffer() throws Exception {
        assertMutatedProviderIsRejected("unexpected_quote");
    }

    private void assertMutatedProviderIsRejected(String mutation) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String providerId = providerId(baseUrl);
        server.createContext("/", exchange -> serveRemote(exchange, providerId, requests, mutation));
        server.start();
        try {
            assertThatThrownBy(() -> gateway(baseUrl, false).load("normal-3c-v1"))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("provider_invalid_response");
        } finally {
            server.stop(0);
        }
    }

    private RetailDataGateway gateway(String baseUrl, boolean fallback) {
        return new RetailDataGateway(
                mapper,
                domains,
                new RetailProviderProperties(
                        true,
                        baseUrl,
                        "test-api-key",
                        fallback,
                        false,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        1_048_576));
    }

    private void serveRemote(
            HttpExchange exchange,
            String providerId,
            List<String> requests,
            String mutation
    ) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                + " " + exchange.getRequestHeaders().getFirst("authorization"));
        if (!"Bearer test-api-key".equals(
                exchange.getRequestHeaders().getFirst("authorization"))) {
            send(exchange, 401, mapper.createObjectNode().put("error", "unauthorized"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestMethod().equals("GET")
                && path.equals("/v1/catalog/normal-3c-v1")) {
            ObjectNode catalog = (ObjectNode) readAsset(
                    "retail-data/normal_3c_catalog_v1.json").deepCopy();
            if (mutation.equals("duplicate_offer")) {
                ArrayNode spus = (ArrayNode) catalog.path("spus");
                String duplicateId = spus.get(0).path("skus").get(0)
                        .path("offers").get(0).path("offer_id").asText();
                ((ObjectNode) spus.get(1).path("skus").get(0)
                        .path("offers").get(0)).put("offer_id", duplicateId);
            }
            String version = catalog.path("catalog_version").asText();
            catalog.set("data_source", metadata(providerId, version));
            send(exchange, 200, catalog);
            return;
        }
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        if (exchange.getRequestMethod().equals("POST")
                && path.equals("/v1/reviews/query")) {
            send(exchange, 200, reviews(body, providerId));
            return;
        }
        if (exchange.getRequestMethod().equals("POST")
                && path.equals("/v1/prices/quote")) {
            ObjectNode response = quotes(body, providerId);
            if (mutation.equals("unexpected_quote")) {
                ((ObjectNode) response.path("quotes").get(0))
                        .put("offer_id", "unexpected-offer");
            }
            send(exchange, 200, response);
            return;
        }
        send(exchange, 404, mapper.createObjectNode().put("error", "not_found"));
    }

    private ObjectNode reviews(JsonNode request, String providerId) throws IOException {
        JsonNode local = readAsset("retail-data/normal_3c_review_aspects_v1.json");
        Set<String> requested = ids(request, "product_ids");
        Set<String> found = new LinkedHashSet<>();
        ArrayNode products = mapper.createArrayNode();
        for (JsonNode product : local.path("products")) {
            String productId = product.path("product_id").asText();
            if (requested.contains(productId)) {
                products.add(product.deepCopy());
                found.add(productId);
            }
        }
        String version = local.path("review_snapshot_version").asText();
        ObjectNode response = mapper.createObjectNode();
        response.put("review_snapshot_version", version);
        response.set("data_source", metadata(providerId, version));
        response.set("products", products);
        ArrayNode missing = response.putArray("missing_product_ids");
        requested.stream().filter(id -> !found.contains(id)).forEach(missing::add);
        return response;
    }

    private ObjectNode quotes(JsonNode request, String providerId) throws IOException {
        JsonNode catalog = readAsset("retail-data/normal_3c_catalog_v1.json");
        Map<String, JsonNode> offers = new HashMap<>();
        for (JsonNode spu : catalog.path("spus")) {
            for (JsonNode sku : spu.path("skus")) {
                for (JsonNode offer : sku.path("offers")) {
                    offers.put(offer.path("offer_id").asText(), offer);
                }
            }
        }
        String version = "remote-quote-v1";
        ObjectNode response = mapper.createObjectNode();
        response.put("quote_version", version);
        response.set("data_source", metadata(providerId, version));
        ArrayNode quotes = response.putArray("quotes");
        for (String offerId : ids(request, "offer_ids")) {
            JsonNode offer = offers.get(offerId);
            ObjectNode quote = quotes.addObject();
            quote.put("offer_id", offerId);
            if (offer == null) {
                quote.put("status", "unavailable");
                continue;
            }
            quote.put("status", "active");
            quote.put("amount", offer.path("price").decimalValue());
            quote.put("stock", offer.path("stock").asInt());
        }
        return response;
    }

    private Set<String> ids(JsonNode request, String field) {
        Set<String> ids = new LinkedHashSet<>();
        request.path(field).forEach(value -> ids.add(value.asText()));
        return ids;
    }

    private JsonNode readAsset(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(input);
        }
    }

    private ObjectNode metadata(String providerId, String version) {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("source", "remote_provider");
        metadata.put("source_version", version);
        metadata.put("provider_id", providerId);
        return metadata;
    }

    private void send(HttpExchange exchange, int status, JsonNode payload) throws IOException {
        sendRaw(exchange, status, mapper.writeValueAsString(payload));
    }

    private static void sendRaw(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String providerId(String baseUrl) throws Exception {
        URI uri = URI.create(baseUrl);
        int port = uri.getPort() >= 0 ? uri.getPort() : 80;
        String canonical = uri.getScheme() + "://" + uri.getHost() + ":" + port + "/";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
        return "retail-" + HexFormat.of().formatHex(digest, 0, 8);
    }
}