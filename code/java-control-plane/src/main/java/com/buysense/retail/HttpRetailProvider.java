package com.buysense.retail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.buysense.platform.CommerceDomainPack;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HttpRetailProvider implements RetailProvider {
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final RetailProviderProperties properties;
    private final HttpClient client;
    private final String providerId;

    HttpRetailProvider(ObjectMapper mapper, RetailProviderProperties properties) {
        this.mapper = mapper;
        this.strictMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        validateRemoteUri(properties);
        this.providerId = providerId(properties.baseUrl());
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
    public JsonNode catalog(CommerceDomainPack pack) {
        return request("GET", "/v1/catalog/" + encode(pack.packId()), null);
    }

    @Override
    public JsonNode reviews(CommerceDomainPack pack, List<String> productIds) {
        return request("POST", "/v1/reviews/query", Map.of(
                "domain_pack_id", pack.packId(),
                "product_ids", productIds));
    }

    @Override
    public JsonNode pricing(CommerceDomainPack pack, List<String> offerIds) {
        return request("POST", "/v1/prices/quote", Map.of(
                "domain_pack_id", pack.packId(),
                "offer_ids", offerIds));
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            URI uri = URI.create(properties.baseUrl().replaceAll("/+$", "") + path);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(properties.readTimeout())
                    .header("accept", "application/json");
            if (!properties.apiKey().isBlank()) {
                request.header("authorization", "Bearer " + properties.apiKey());
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                byte[] encoded = mapper.writeValueAsBytes(body);
                if (encoded.length > 256 * 1_024) {
                    throw new RetailDataGateway.ProviderException("provider_request_too_large");
                }
                request.header("content-type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(encoded));
            }
            HttpResponse<InputStream> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new RetailDataGateway.ProviderException("provider_http_error");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                response.body().close();
                throw new RetailDataGateway.ProviderException("provider_invalid_content_type");
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(properties.maxResponseBytes() + 1);
            }
            if (bytes.length > properties.maxResponseBytes()) {
                throw new RetailDataGateway.ProviderException("provider_response_too_large");
            }
            JsonNode result;
            try {
                result = strictMapper.readTree(bytes);
            } catch (JsonProcessingException error) {
                throw new RetailDataGateway.ProviderException("provider_invalid_response");
            }
            if (!result.isObject()) {
                throw new RetailDataGateway.ProviderException("provider_invalid_response");
            }
            return result;
        } catch (java.net.http.HttpTimeoutException error) {
            throw new RetailDataGateway.ProviderException("provider_timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RetailDataGateway.ProviderException("provider_interrupted");
        } catch (RetailDataGateway.ProviderException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            throw new RetailDataGateway.ProviderException("provider_network_error");
        }
    }

    private static void validateRemoteUri(RetailProviderProperties properties) {
        URI uri;
        try {
            uri = URI.create(properties.baseUrl());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("retail provider URL is invalid", error);
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("retail provider URL must be an absolute HTTP(S) URL");
        }
        boolean loopback = uri.getHost().equalsIgnoreCase("localhost")
                || uri.getHost().equals("127.0.0.1") || uri.getHost().equals("::1");
        if (uri.getScheme().equals("http") && !loopback && !properties.allowInsecureHttp()) {
            throw new IllegalArgumentException(
                    "non-loopback HTTP provider requires explicit opt-in");
        }
    }

    static String providerId(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains(":")) host = "[" + host + "]";
            int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            if (path.isBlank()) path = "/";
            String canonical = scheme + "://" + host + ":" + port + path;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "retail-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception error) {
            throw new IllegalStateException("unable to fingerprint retail provider", error);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
