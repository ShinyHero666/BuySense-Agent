package com.buysense.retail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.platform.ExtensionRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetailDataGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainPackRegistry domains = new DomainPackRegistry(mapper, new ExtensionRegistry());

    @Test
    void fallsBackToVersionedSnapshotsAndExposesLowCardinalityHealthTelemetry() throws Exception {
        HttpServer server = failingServer();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RetailDataGateway gateway = gateway(baseUrl, true);
            RetailDataGateway equivalent = gateway(baseUrl + "/", true);

            var snapshot = gateway.load("outdoor-camping-v1");

            assertThat(snapshot.domainPackId()).isEqualTo("outdoor-camping-v1");
            assertThat(snapshot.products()).isNotEmpty()
                    .allSatisfy(product -> assertThat(product.source()).isEqualTo("local_snapshot"));
            assertThat(gateway.health().get("catalog").get("providerId"))
                    .isEqualTo(equivalent.health().get("catalog").get("providerId"));
            gateway.health().values().forEach(source -> {
                assertThat(source).containsEntry("configuredMode", "http")
                        .containsEntry("effectiveSource", "local_snapshot")
                        .containsEntry("status", "degraded")
                        .containsEntry("fallbackActive", true)
                        .containsEntry("lastErrorCode", "provider_http_error");
                Map<?, ?> telemetry = (Map<?, ?>) source.get("telemetry");
                assertThat(((Number) telemetry.get("requests")).longValue()).isEqualTo(1);
                assertThat(((Number) telemetry.get("errors")).longValue()).isEqualTo(1);
                assertThat(((Number) telemetry.get("fallbacks")).longValue()).isEqualTo(1);
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClosedWhenRemoteFallbackIsDisabled() throws Exception {
        HttpServer server = failingServer();
        try {
            RetailDataGateway gateway = gateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(), false);

            assertThatThrownBy(() -> gateway.load("normal-3c-v1"))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("provider_http_error");
            assertThat(gateway.health().values()).allSatisfy(source ->
                    assertThat(source).containsEntry("status", "down")
                            .containsEntry("fallbackActive", false));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void confirmationFailsClosedInsteadOfUsingAFallbackSnapshotAsLivePricing() throws Exception {
        HttpServer server = failingServer();
        try {
            RetailDataGateway gateway = gateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(), true);
            var fallback = gateway.load("normal-3c-v1");

            assertThatThrownBy(() -> gateway.revalidateSelection(
                    "normal-3c-v1", java.util.List.of(fallback.products().get(0))))
                    .isInstanceOf(RetailDataGateway.ProviderException.class)
                    .extracting(error -> ((RetailDataGateway.ProviderException) error).code())
                    .isEqualTo("confirmation_requires_remote_pricing");
        } finally {
            server.stop(0);
        }
    }
    @Test
    void rejectsPlainHttpForNonLoopbackProvidersUnlessExplicitlyEnabled() {
        assertThatThrownBy(() -> gateway("http://example.com/provider", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit opt-in");
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
                        1_048_576),
                ShopifyProviderProperties.disabled());
    }

    private HttpServer failingServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}