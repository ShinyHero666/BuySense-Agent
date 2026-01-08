package com.buysense.retail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HttpReviewEvidenceProvider implements ReviewEvidenceProvider {
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final ReviewEvidenceProviderProperties properties;
    private final HttpClient client;
    private final URI endpoint;
    private final String providerId;

    HttpReviewEvidenceProvider(
            ObjectMapper mapper,
            ReviewEvidenceProviderProperties properties
    ) {
        this.mapper = mapper;
        this.strictMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        URI base = validateRemoteUri(properties);
        this.endpoint = URI.create(base.toString().replaceAll("/+$", "")
                + properties.queryPath());
        this.providerId = providerId(base, properties.queryPath());
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String sourceType() {
        return "remote_provider";
    }

    @Override
    public ObjectNode fetch(String domainPackId, List<String> productIds) {
        try {
            byte[] encoded = mapper.writeValueAsBytes(Map.of(
                    "domain_pack_id", domainPackId,
                    "product_ids", productIds));
            if (encoded.length > 256 * 1_024) {
                throw new ReviewEvidenceGateway.ProviderException(
                        "review_provider_request_too_large");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(properties.readTimeout())
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encoded));
            if (!properties.apiKey().isBlank()) {
                request.header("authorization", "Bearer " + properties.apiKey());
            }
            HttpResponse<InputStream> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new ReviewEvidenceGateway.ProviderException(
                        response.statusCode() == 429
                                ? "review_provider_rate_limited"
                                : "review_provider_http_error");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                response.body().close();
                throw new ReviewEvidenceGateway.ProviderException(
                        "review_provider_invalid_content_type");
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(properties.maxResponseBytes() + 1);
            }
            if (bytes.length > properties.maxResponseBytes()) {
                throw new ReviewEvidenceGateway.ProviderException(
                        "review_provider_response_too_large");
            }
            JsonNode result;
            try {
                result = strictMapper.readTree(bytes);
            } catch (JsonProcessingException error) {
                throw new ReviewEvidenceGateway.ProviderException(
                        "review_provider_invalid_response");
            }
            if (!result.isObject()) {
                throw new ReviewEvidenceGateway.ProviderException(
                        "review_provider_invalid_response");
            }
            return (ObjectNode) result;
        } catch (java.net.http.HttpTimeoutException error) {
            throw new ReviewEvidenceGateway.ProviderException("review_provider_timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ReviewEvidenceGateway.ProviderException("review_provider_interrupted");
        } catch (ReviewEvidenceGateway.ProviderException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            throw new ReviewEvidenceGateway.ProviderException("review_provider_network_error");
        }
    }

    private static URI validateRemoteUri(ReviewEvidenceProviderProperties properties) {
        URI uri;
        try {
            uri = URI.create(properties.baseUrl());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("review provider URL is invalid", error);
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "review provider URL must be an absolute HTTP(S) URL");
        }
        boolean loopback = uri.getHost().equalsIgnoreCase("localhost")
                || uri.getHost().equals("127.0.0.1") || uri.getHost().equals("::1");
        if (uri.getScheme().equals("http") && !loopback && !properties.allowInsecureHttp()) {
            throw new IllegalArgumentException(
                    "non-loopback HTTP review provider requires explicit opt-in");
        }
        return uri;
    }

    private static String providerId(URI base, String path) {
        try {
            int port = base.getPort() >= 0 ? base.getPort()
                    : base.getScheme().equals("https") ? 443 : 80;
            String basePath = base.getPath() == null ? ""
                    : base.getPath().replaceAll("/+$", "");
            String canonical = base.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + base.getHost().toLowerCase(Locale.ROOT) + ":" + port
                    + basePath + path;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "review-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception error) {
            throw new IllegalStateException("unable to fingerprint review provider", error);
        }
    }
}
