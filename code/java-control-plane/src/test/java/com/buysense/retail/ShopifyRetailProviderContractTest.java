package com.buysense.retail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.platform.ExtensionRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopifyRetailProviderContractTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainPackRegistry domains = new DomainPackRegistry(
            mapper, new ExtensionRegistry());

    @Test
    void javaProviderLoadsShopifyGraphQlDirectly() throws Exception {
        try (ShopifyMock shopify = new ShopifyMock(Mode.NORMAL)) {
            RetailDataGateway gateway = gateway(shopify, false);

            RetailDataSnapshot snapshot = gateway.load("normal-3c-v1");

            assertThat(snapshot.products()).hasSize(3).allSatisfy(product -> {
                assertThat(product.source()).isEqualTo("remote_provider");
                assertThat(product.providerId()).startsWith("shopify-");
            });
            assertThat(snapshot.evidenceByProduct()).hasSize(3);
            assertThat(shopify.queries).hasSize(3)
                    .allSatisfy(query -> assertThat(query.path())
                            .isEqualTo("/graphql.json"));
            assertThat(shopify.queries).allSatisfy(query ->
                    assertThat(query.token()).isEqualTo("test-shopify-token"));
            assertThat(shopify.catalogCalls).hasValue(1);
            assertThat(shopify.reviewCalls).hasValue(1);
            assertThat(shopify.pricingCalls).hasValue(1);
            gateway.health().values().forEach(source ->
                    assertThat(source).containsEntry("configuredMode", "shopify")
                            .containsEntry("effectiveSource", "remote_provider")
                            .containsEntry("status", "up")
                            .containsEntry("fallbackActive", false));
        }
    }

    @Test
    void followsBoundedShopifyCatalogPagination() throws Exception {
        try (ShopifyMock shopify = new ShopifyMock(Mode.PAGINATED)) {
            RetailDataSnapshot snapshot = gateway(shopify, false).load("normal-3c-v1");

            assertThat(snapshot.products()).hasSize(3);
            assertThat(shopify.catalogCalls).hasValue(2);
            assertThat(shopify.reviewCalls).hasValue(1);
            assertThat(shopify.pricingCalls).hasValue(1);
        }
    }

    @Test
    void confirmationRefreshesShopifyCatalogAndQuote() throws Exception {
        try (ShopifyMock shopify = new ShopifyMock(Mode.PRICE_INCREASE)) {
            RetailDataGateway gateway = gateway(shopify, false);
            RetailDataSnapshot proposal = gateway.load("normal-3c-v1");
            var selected = proposal.products().get(0);

            RetailDataGateway.RevalidatedSelection current = gateway.revalidateSelection(
                    "normal-3c-v1", List.of(selected));

            assertThat(current.totalPrice()).isGreaterThan(selected.price());
            assertThat(current.items()).singleElement().satisfies(product ->
                    assertThat(product.source()).isEqualTo("remote_provider"));
            assertThat(shopify.catalogCalls).hasValue(2);
            assertThat(shopify.reviewCalls).hasValue(2);
            assertThat(shopify.pricingCalls).hasValue(2);
        }
    }

    @Test
    void writeScopedShopifyTokenFailsClosedEvenWhenFallbackIsConfigured() throws Exception {
        try (ShopifyMock shopify = new ShopifyMock(Mode.WRITE_SCOPE)) {
            RetailDataGateway gateway = gateway(shopify, true);

            assertThatThrownBy(() -> gateway.load("normal-3c-v1"))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("provider_write_scope_forbidden");
            gateway.health().values().forEach(source ->
                    assertThat(source).containsEntry("fallbackActive", false)
                            .containsEntry("status", "down"));
        }
    }

    @Test
    void transientShopifyFailureMayFallbackButCannotConfirmSnapshotPricing() throws Exception {
        try (ShopifyMock shopify = new ShopifyMock(Mode.HTTP_ERROR)) {
            RetailDataGateway gateway = gateway(shopify, true);
            RetailDataSnapshot fallback = gateway.load("normal-3c-v1");

            assertThat(fallback.products()).isNotEmpty().allSatisfy(product ->
                    assertThat(product.source()).isEqualTo("local_snapshot"));
            assertThatThrownBy(() -> gateway.revalidateSelection(
                    "normal-3c-v1", List.of(fallback.products().get(0))))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("confirmation_requires_remote_pricing");
        }
    }

    @Test
    void rejectsDuplicateJsonFieldsFromShopify() throws Exception {
        try (ShopifyMock shopify = new ShopifyMock(Mode.DUPLICATE_JSON)) {
            RetailDataGateway gateway = gateway(shopify, false);

            assertThatThrownBy(() -> gateway.load("normal-3c-v1"))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("provider_invalid_json");
        }
    }

    @Test
    void rejectsShopifyApiVersionDowngradeWithoutFallback() throws Exception {
        try (ShopifyMock shopify = new ShopifyMock(Mode.VERSION_MISMATCH)) {
            RetailDataGateway gateway = gateway(shopify, true);

            assertThatThrownBy(() -> gateway.load("normal-3c-v1"))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("provider_api_version_mismatch");
        }
    }

    private RetailDataGateway gateway(ShopifyMock shopify, boolean fallback) {
        ShopifyProviderProperties properties = new ShopifyProviderProperties(
                true,
                "test-store.myshopify.com",
                "test-shopify-token",
                ShopifyProviderProperties.SUPPORTED_API_VERSION,
                fallback,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                1_048_576,
                Duration.ofMinutes(5));
        ShopifyRetailProvider provider = new ShopifyRetailProvider(
                mapper, properties, shopify.endpoint(), CLOCK);
        return RetailDataGateway.withProvider(mapper, domains, provider, fallback, CLOCK);
    }

    private enum Mode {
        NORMAL,
        PRICE_INCREASE,
        PAGINATED,
        WRITE_SCOPE,
        VERSION_MISMATCH,
        HTTP_ERROR,
        DUPLICATE_JSON
    }

    private final class ShopifyMock implements AutoCloseable {
        private final HttpServer server;
        private final Mode mode;
        private final AtomicInteger catalogCalls = new AtomicInteger();
        private final AtomicInteger reviewCalls = new AtomicInteger();
        private final AtomicInteger pricingCalls = new AtomicInteger();
        private final List<QueryCall> queries = new ArrayList<>();

        private ShopifyMock(Mode mode) throws IOException {
            this.mode = mode;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/graphql.json", this::serve);
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/graphql.json");
        }

        private void serve(HttpExchange exchange) throws IOException {
            String token = exchange.getRequestHeaders().getFirst("X-Shopify-Access-Token");
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String query = request.path("query").asText();
            queries.add(new QueryCall(exchange.getRequestURI().getPath(), token, query));
            if (mode == Mode.HTTP_ERROR) {
                send(exchange, 503, mapper.createObjectNode().put("error", "unavailable"));
                return;
            }
            if (mode == Mode.DUPLICATE_JSON) {
                exchange.getResponseHeaders().set(
                        "X-Shopify-API-Version",
                        ShopifyProviderProperties.SUPPORTED_API_VERSION);
                sendRaw(exchange, 200, "{\"data\":{},\"data\":{}}");
                return;
            }
            ObjectNode payload = mapper.createObjectNode();
            if (query.contains("BuySenseCatalog")) {
                catalogCalls.incrementAndGet();
                payload.set("data", catalogData(
                        mode == Mode.WRITE_SCOPE,
                        request.path("variables").path("after")));
            } else if (query.contains("BuySenseReviews")) {
                reviewCalls.incrementAndGet();
                payload.set("data", reviewData(request.path("variables").path("ids")));
            } else if (query.contains("BuySensePricing")) {
                int call = pricingCalls.incrementAndGet();
                payload.set("data", pricingData(
                        request.path("variables").path("ids"),
                        mode == Mode.PRICE_INCREASE && call > 1));
            } else {
                payload.putArray("errors").addObject()
                        .putObject("extensions").put("code", "ACCESS_DENIED");
            }
            exchange.getResponseHeaders().set(
                    "X-Shopify-API-Version",
                    mode == Mode.VERSION_MISMATCH ? "2026-04"
                            : ShopifyProviderProperties.SUPPORTED_API_VERSION);
            send(exchange, 200, payload);
        }

        private ObjectNode catalogData(boolean writeScope, JsonNode after) {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode scopes = data.putObject("currentAppInstallation")
                    .putArray("accessScopes");
            scopes.addObject().put("handle", "read_products");
            if (writeScope) scopes.addObject().put("handle", "write_products");
            ObjectNode products = data.putObject("products");
            boolean firstPage = mode != Mode.PAGINATED || after.isMissingNode() || after.isNull();
            boolean hasNext = mode == Mode.PAGINATED && firstPage;
            ObjectNode pageInfo = products.putObject("pageInfo")
                    .put("hasNextPage", hasNext);
            if (hasNext) pageInfo.put("endCursor", "catalog-page-2");
            else pageInfo.putNull("endCursor");
            ArrayNode nodes = products.putArray("nodes");
            if (mode != Mode.PAGINATED || firstPage) {
                nodes.add(product(101, 201, "Phone Pro", "phone", "Apple",
                        List.of("usb-c"), List.of("usb-pd", "bluetooth"), "5999.00"));
            }
            if (mode != Mode.PAGINATED || !firstPage) {
                nodes.add(product(102, 202, "Wireless Headphones", "headphones", "Soundcore",
                        List.of("usb-c"), List.of("bluetooth"), "699.00"));
                nodes.add(product(103, 203, "Fast Charger", "charger", "Anker",
                        List.of("usb-c"), List.of("usb-pd", "pps"), "199.00"));
            }
            return data;
        }

        private ObjectNode product(
                int productId,
                int variantId,
                String title,
                String category,
                String brand,
                List<String> connectors,
                List<String> protocols,
                String price
        ) {
            ObjectNode product = mapper.createObjectNode();
            product.put("id", "gid://shopify/Product/" + productId);
            product.put("title", title);
            product.put("vendor", brand);
            product.put("productType", category);
            product.putArray("tags").add("buysense-domain-pack-normal-3c-v1");
            product.put("updatedAt", "2026-08-14T10:00:00Z");
            product.put("requiresSellingPlan", false);
            product.put("publishedInContext", true);
            ObjectNode productMetadata = product.putObject("sarProduct");
            productMetadata.put("type", "json");
            ObjectNode productJson = productMetadata.putObject("jsonValue");
            productJson.put("domain_pack_id", "normal-3c-v1");
            productJson.put("category", category);
            productJson.put("brand", brand);
            productJson.putArray("tags").add("featured");

            ObjectNode variants = product.putObject("variants");
            variants.putObject("pageInfo").put("hasNextPage", false);
            ObjectNode variant = variants.putArray("nodes").addObject();
            variant.put("id", "gid://shopify/ProductVariant/" + variantId);
            variant.put("title", title + " Standard");
            variant.put("sku", "SKU-" + variantId);
            variant.put("updatedAt", "2026-08-14T10:00:00Z");
            variant.put("requiresComponents", false);
            variant.put("availableForSale", true);
            variant.put("sellableOnlineQuantity", 20);
            ObjectNode priceNode = variant.putObject("contextualPricing").putObject("price");
            priceNode.put("amount", price);
            priceNode.put("currencyCode", "CNY");
            ObjectNode variantMetadata = variant.putObject("sarVariant");
            variantMetadata.put("type", "json");
            ObjectNode variantJson = variantMetadata.putObject("jsonValue");
            variantJson.put("ecosystem", "universal");
            variantJson.set("connectors", mapper.valueToTree(connectors));
            variantJson.set("protocols", mapper.valueToTree(protocols));
            variantJson.put("sponsored", false);
            variantJson.put("ad_bid", 0.2);
            variantJson.put("ad_quality", 0.8);
            return product;
        }

        private ObjectNode reviewData(JsonNode ids) {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode nodes = data.putArray("nodes");
            for (JsonNode id : ids) {
                ObjectNode product = nodes.addObject();
                product.put("__typename", "Product");
                product.put("id", id.asText());
                product.put("updatedAt", "2026-08-14T10:00:00Z");
                ObjectNode rating = product.putObject("reviewsRating");
                rating.put("type", "rating");
                ObjectNode ratingJson = rating.putObject("jsonValue");
                ratingJson.put("value", "4.6");
                ratingJson.put("scale_min", "1.0");
                ratingJson.put("scale_max", "5.0");
                ObjectNode count = product.putObject("reviewsRatingCount");
                count.put("type", "number_integer");
                count.put("value", "120");
            }
            return data;
        }

        private ObjectNode pricingData(JsonNode ids, boolean increase) {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode nodes = data.putArray("nodes");
            for (JsonNode id : ids) {
                int variant = Integer.parseInt(id.asText().substring(
                        id.asText().lastIndexOf('/') + 1));
                BigDecimalValue base = switch (variant) {
                    case 201 -> new BigDecimalValue("5999.00");
                    case 202 -> new BigDecimalValue("699.00");
                    default -> new BigDecimalValue("199.00");
                };
                ObjectNode node = nodes.addObject();
                node.put("__typename", "ProductVariant");
                node.put("id", id.asText());
                node.put("updatedAt", "2026-08-14T10:01:00Z");
                node.put("requiresComponents", false);
                node.put("availableForSale", true);
                node.put("sellableOnlineQuantity", 20);
                node.putObject("product").put("publishedInContext", true);
                ObjectNode price = node.putObject("contextualPricing").putObject("price");
                price.put("amount", increase ? base.plus("100.00") : base.value());
                price.put("currencyCode", "CNY");
            }
            return data;
        }

        private void send(HttpExchange exchange, int status, JsonNode payload) throws IOException {
            sendRaw(exchange, status, mapper.writeValueAsString(payload));
        }

        private void sendRaw(HttpExchange exchange, int status, String payload) throws IOException {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record QueryCall(String path, String token, String document) {
    }

    private record BigDecimalValue(String value) {
        String plus(String amount) {
            return new java.math.BigDecimal(value)
                    .add(new java.math.BigDecimal(amount)).toPlainString();
        }
    }
}
