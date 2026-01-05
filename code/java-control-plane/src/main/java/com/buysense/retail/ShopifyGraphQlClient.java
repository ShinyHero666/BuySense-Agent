package com.buysense.retail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

final class ShopifyGraphQlClient {
    private static final int MAX_REQUEST_BYTES = 256 * 1_024;

    static final String CATALOG_QUERY = """
            query BuySenseCatalog($first: Int!, $after: String, $query: String!) {
              currentAppInstallation {
                accessScopes { handle }
              }
              products(first: $first, after: $after, query: $query, sortKey: ID) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id
                  title
                  vendor
                  productType
                  tags
                  updatedAt
                  requiresSellingPlan
                  publishedInContext(context: {country: CN})
                  sarProduct: metafield(namespace: "buysense", key: "sar_product") {
                    type
                    value
                    jsonValue
                  }
                  variants(first: 25) {
                    pageInfo { hasNextPage }
                    nodes {
                      id
                      title
                      sku
                      updatedAt
                      requiresComponents
                      availableForSale
                      sellableOnlineQuantity
                      contextualPricing(context: {country: CN}) {
                        price { amount currencyCode }
                      }
                      sarVariant: metafield(namespace: "buysense", key: "sar_variant") {
                        type
                        value
                        jsonValue
                      }
                    }
                  }
                }
              }
            }
            """;

    static final String REVIEWS_QUERY = """
            query BuySenseReviews($ids: [ID!]!) {
              nodes(ids: $ids) {
                __typename
                id
                ... on Product {
                  updatedAt
                  reviewsRating: metafield(namespace: "reviews", key: "rating") {
                    type
                    value
                    jsonValue
                  }
                  reviewsRatingCount: metafield(namespace: "reviews", key: "rating_count") {
                    type
                    value
                  }
                }
              }
            }
            """;

    static final String PRICING_QUERY = """
            query BuySensePricing($ids: [ID!]!) {
              nodes(ids: $ids) {
                __typename
                id
                ... on ProductVariant {
                  updatedAt
                  requiresComponents
                  availableForSale
                  sellableOnlineQuantity
                  product { publishedInContext(context: {country: CN}) }
                  contextualPricing(context: {country: CN}) {
                    price { amount currencyCode }
                  }
                }
              }
            }
            """;

    private static final Set<String> FIXED_QUERIES = Set.of(
            CATALOG_QUERY, REVIEWS_QUERY, PRICING_QUERY);

    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final ShopifyProviderProperties properties;
    private final URI endpoint;
    private final HttpClient client;
    private final ReentrantLock requestLock = new ReentrantLock();

    ShopifyGraphQlClient(
            ObjectMapper mapper,
            ShopifyProviderProperties properties,
            URI endpoint
    ) {
        this.mapper = mapper;
        this.strictMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(noProxy())
                .build();
    }

    JsonNode execute(String document, Map<String, ?> variables) {
        if (!FIXED_QUERIES.contains(document)) {
            throw new IllegalArgumentException("Shopify GraphQL query is not allow-listed");
        }
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "query", document,
                    "variables", variables));
            if (body.length > MAX_REQUEST_BYTES) {
                throw new RetailDataGateway.ProviderException("provider_request_too_large");
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(properties.readTimeout())
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("X-Shopify-Access-Token", properties.adminAccessToken())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            requestLock.lockInterruptibly();
            try {
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                return decode(response);
            } finally {
                requestLock.unlock();
            }
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

    private JsonNode decode(HttpResponse<InputStream> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new RetailDataGateway.ProviderException(httpError(response.statusCode()));
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            response.body().close();
            throw new RetailDataGateway.ProviderException("provider_invalid_content_type");
        }
        String apiVersion = response.headers()
                .firstValue("X-Shopify-API-Version").orElse("");
        if (!properties.apiVersion().equals(apiVersion)) {
            response.body().close();
            throw new RetailDataGateway.ProviderException("provider_api_version_mismatch");
        }
        byte[] raw;
        try (InputStream input = response.body()) {
            raw = input.readNBytes(properties.maxResponseBytes() + 1);
        }
        if (raw.length > properties.maxResponseBytes()) {
            throw new RetailDataGateway.ProviderException("provider_response_too_large");
        }
        JsonNode payload;
        try {
            payload = strictMapper.readTree(raw);
        } catch (JsonProcessingException error) {
            throw new RetailDataGateway.ProviderException("provider_invalid_json");
        }
        if (!payload.isObject()) {
            throw new RetailDataGateway.ProviderException("provider_invalid_response");
        }
        JsonNode errors = payload.get("errors");
        if (errors != null && (!errors.isArray() || !errors.isEmpty())) {
            throw new RetailDataGateway.ProviderException(graphqlError(errors));
        }
        JsonNode data = payload.path("data");
        if (!data.isObject()) {
            throw new RetailDataGateway.ProviderException("provider_invalid_response");
        }
        return data;
    }

    private static String graphqlError(JsonNode errors) {
        Set<String> codes = new LinkedHashSet<>();
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                JsonNode code = error.path("extensions").path("code");
                if (code.isTextual()) codes.add(code.textValue());
            }
        }
        if (codes.contains("ACCESS_DENIED")) return "provider_access_forbidden";
        if (codes.contains("MAX_COST_EXCEEDED")) return "provider_query_cost_exceeded";
        if (codes.contains("SHOP_INACTIVE")) return "provider_shop_inactive";
        if (codes.contains("THROTTLED")) return "provider_rate_limited";
        return "provider_graphql_error";
    }

    private static String httpError(int status) {
        if (status == 401) return "provider_authentication_failed";
        if (status == 403) return "provider_access_forbidden";
        if (status == 429) return "provider_rate_limited";
        if (status >= 400 && status < 500) return "provider_request_rejected";
        return "provider_http_error";
    }

    private static ProxySelector noProxy() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException error) {
                // HttpClient reports the failure through the provider boundary.
            }
        };
    }
}
