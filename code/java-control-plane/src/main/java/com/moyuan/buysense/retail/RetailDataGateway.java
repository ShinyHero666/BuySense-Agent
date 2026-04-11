package com.moyuan.buysense.retail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.domain.Product;
import com.moyuan.buysense.platform.CommerceDomainPack;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.retail.RetailDataSnapshot.CatalogItemMetadata;
import com.moyuan.buysense.retail.RetailDataSnapshot.CompatibilityRule;
import com.moyuan.buysense.retail.RetailDataSnapshot.ProductEvidence;
import com.moyuan.buysense.retail.RetailDataSnapshot.ReviewAspect;
import com.moyuan.buysense.retail.RetailSourceState.DataSourceMetadata;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public final class RetailDataGateway {
    private static final Pattern PROVIDER_ID = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");
    private static final Pattern SAFE_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final List<String> RESOURCES = List.of("catalog", "reviews", "pricing");

    private final ObjectMapper mapper;
    private final ObjectMapper providerMapper;
    private final DomainPackRegistry domains;
    private final RetailProviderProperties properties;
    private final HttpClient client;
    private final String remoteProviderId;
    private final Map<String, RetailDataSnapshot> localSnapshots = new ConcurrentHashMap<>();
    private final Map<String, RetailSourceState> states = new LinkedHashMap<>();

    public RetailDataGateway(
            ObjectMapper mapper,
            DomainPackRegistry domains,
            RetailProviderProperties properties
    ) {
        this.mapper = mapper;
        this.providerMapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.domains = domains;
        this.properties = properties;
        validateRemoteUri(properties);
        this.remoteProviderId = properties.enabled() ? providerId(properties.baseUrl()) : null;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        for (String resource : RESOURCES) {
            states.put(resource, new RetailSourceState(
                    properties.enabled() ? "http" : "static",
                    properties.enabled() ? remoteProviderId : null));
        }
        domains.list().forEach(pack -> {
            RetailDataSnapshot snapshot = loadLocal(pack);
            validateRequiredInventory(pack, snapshot);
            localSnapshots.put(pack.packId(), snapshot);
        });
    }

    public RetailDataSnapshot load(String domainPackId) {
        CommerceDomainPack pack = domains.require(domainPackId);
        if (!properties.enabled()) {
            RetailDataSnapshot local = localSnapshots.get(pack.packId());
            markLocal(local, false, null);
            return local;
        }

        RESOURCES.forEach(name -> states.get(name).requestStarted());
        try {
            RetailDataSnapshot remote = loadRemote(pack);
            states.get("catalog").succeeded(remoteMetadata(remote.catalogVersion()));
            states.get("reviews").succeeded(remoteMetadata(remote.reviewVersion()));
            states.get("pricing").succeeded(remoteMetadata(remote.sources().get("pricingVersion")));
            return remote;
        } catch (ProviderException error) {
            RESOURCES.forEach(name -> states.get(name).failed(error.code()));
            if (!properties.fallbackEnabled()) throw error;
            RetailDataSnapshot fallback = localSnapshots.get(pack.packId());
            markLocal(fallback, true, error.code());
            return fallback;
        } catch (RuntimeException error) {
            ProviderException sanitized = new ProviderException("provider_invalid_response");
            RESOURCES.forEach(name -> states.get(name).failed(sanitized.code()));
            if (!properties.fallbackEnabled()) throw sanitized;
            RetailDataSnapshot fallback = localSnapshots.get(pack.packId());
            markLocal(fallback, true, sanitized.code());
            return fallback;
        }
    }

    public RevalidatedSelection revalidateSelection(
            String domainPackId,
            List<Product> proposedItems
    ) {
        if (proposedItems == null || proposedItems.isEmpty()) {
            throw new ProviderException("proposal_selection_empty");
        }
        Set<String> requestedIds = proposedItems.stream()
                .map(Product::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (requestedIds.size() != proposedItems.size()) {
            throw new ProviderException("proposal_selection_duplicate");
        }

        RetailDataSnapshot current = load(domainPackId);
        Map<String, Product> currentById = current.products().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Product::id,
                        product -> product,
                        (left, right) -> {
                            throw new ProviderException("provider_duplicate_product");
                        },
                        LinkedHashMap::new));
        List<Product> refreshed = requestedIds.stream().map(productId -> {
            Product product = currentById.get(productId);
            if (product == null) throw new ProviderException("proposal_product_unavailable");
            if (!current.inStock(product) || product.stock() <= 0) {
                throw new ProviderException("proposal_product_out_of_stock");
            }
            if (properties.enabled() && !"remote_provider".equals(product.source())) {
                throw new ProviderException("confirmation_requires_remote_pricing");
            }
            return product;
        }).toList();

        CommerceDomainPack pack = domains.require(domainPackId);
        if (!current.compatible(refreshed, pack.primaryCategory())) {
            throw new ProviderException("proposal_compatibility_changed");
        }
        BigDecimal total = refreshed.stream()
                .map(Product::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RevalidatedSelection(
                List.copyOf(refreshed),
                total,
                current.catalogVersion(),
                current.sources().get("pricingVersion"),
                properties.enabled() ? remoteProviderId : pack.packId());
    }
    public Map<String, Map<String, Object>> health() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        states.forEach((name, state) -> result.put(name, state.snapshot()));
        return Map.copyOf(result);
    }

    public int localProductCount(String domainPackId) {
        return localSnapshots.get(domains.require(domainPackId).packId()).products().size();
    }

    public int localSpuCount(String domainPackId) {
        return (int) localSnapshots.get(domains.require(domainPackId).packId())
                .catalogMetadata().values().stream()
                .map(CatalogItemMetadata::spuId)
                .distinct()
                .count();
    }

    public String localCatalogVersion(String domainPackId) {
        return localSnapshots.get(domains.require(domainPackId).packId()).catalogVersion();
    }
    private RetailDataSnapshot loadLocal(CommerceDomainPack pack) {
        try {
            JsonNode catalog = read(pack.assets().catalog());
            JsonNode reviews = read(pack.assets().reviews());
            JsonNode compatibility = read(pack.assets().compatibility());
            CatalogParse parsedCatalog = parseCatalog(
                    pack, catalog, localMetadata(text(catalog, "catalog_version"), pack.packId()));
            ReviewParse parsedReviews = parseReviews(
                    reviews,
                    localMetadata(text(reviews, "review_snapshot_version"), pack.packId()));
            return snapshot(
                    pack,
                    parsedCatalog,
                    parsedReviews,
                    parseCompatibility(pack, compatibility),
                    "local_snapshot",
                    "realtime-" + parsedCatalog.version());
        } catch (IOException error) {
            throw new IllegalStateException("failed to load local retail snapshot: " + pack.packId(), error);
        }
    }

    private RetailDataSnapshot loadRemote(CommerceDomainPack pack) {
        JsonNode catalog = request("GET", "/v1/catalog/" + encode(pack.packId()), null);
        String catalogVersion = text(catalog, "catalog_version");
        CatalogParse parsedCatalog = parseCatalog(
                pack, catalog, remoteMetadata(catalog.path("data_source"), catalogVersion));

        List<String> productIds = parsedCatalog.metadata().values().stream()
                .map(CatalogItemMetadata::spuId).distinct().limit(100).toList();
        JsonNode reviews = request("POST", "/v1/reviews/query", Map.of(
                "domain_pack_id", pack.packId(),
                "product_ids", productIds));
        String reviewVersion = text(reviews, "review_snapshot_version");
        ReviewParse parsedReviews = parseReviews(
                reviews, remoteMetadata(reviews.path("data_source"), reviewVersion));
        validateReviewPartition(reviews, productIds, parsedReviews.evidence().keySet());

        List<String> offerIds = parsedCatalog.metadata().values().stream()
                .map(CatalogItemMetadata::offerId).distinct().limit(100).toList();
        JsonNode pricing = request("POST", "/v1/prices/quote", Map.of(
                "domain_pack_id", pack.packId(),
                "offer_ids", offerIds));
        String pricingVersion = text(pricing, "quote_version");
        remoteMetadata(pricing.path("data_source"), pricingVersion);
        CatalogParse quotedCatalog = applyQuotes(parsedCatalog, pricing);

        try {
            CompatibilityParse compatibility = parseCompatibility(pack, read(pack.assets().compatibility()));
            RetailDataSnapshot remote = snapshot(
                    pack,
                    quotedCatalog,
                    parsedReviews,
                    compatibility,
                    "remote_provider",
                    pricingVersion);
            validateRequiredInventory(pack, remote);
            return remote;
        } catch (IOException error) {
            throw new IllegalStateException("failed to load compatibility graph", error);
        }
    }

    private RetailDataSnapshot snapshot(
            CommerceDomainPack pack,
            CatalogParse catalog,
            ReviewParse reviews,
            CompatibilityParse compatibility,
            String source,
            String pricingVersion
    ) {
        return new RetailDataSnapshot(
                pack.packId(),
                catalog.version(),
                reviews.version(),
                compatibility.version(),
                catalog.products(),
                catalog.metadata(),
                reviews.evidence(),
                compatibility.rules(),
                Map.of(
                        "catalog", source,
                        "reviews", reviews.source().source(),
                        "pricing", source,
                        "pricingVersion", pricingVersion));
    }

    private CatalogParse parseCatalog(
            CommerceDomainPack pack,
            JsonNode root,
            DataSourceMetadata source
    ) {
        String version = safeVersion(text(root, "catalog_version"), "catalog version");
        if (!source.sourceVersion().equals(version)) {
            throw new IllegalArgumentException("catalog source version mismatch");
        }
        var allowedCategories = pack.categories().stream()
                .map(CommerceDomainPack.CategoryDefinition::id)
                .collect(java.util.stream.Collectors.toSet());
        List<Product> products = new ArrayList<>();
        Map<String, CatalogItemMetadata> metadata = new LinkedHashMap<>();
        Set<String> seenSpuIds = new LinkedHashSet<>();
        Set<String> seenSkuIds = new LinkedHashSet<>();
        Set<String> seenOfferIds = new LinkedHashSet<>();
        for (JsonNode spu : array(root, "spus", 2_000)) {
            String spuId = boundedText(spu, "spu_id", 128);
            if (!seenSpuIds.add(spuId)) throw new IllegalArgumentException("duplicate catalog SPU id");
            String category = boundedText(spu, "category", 64);
            if (!allowedCategories.contains(category)) {
                throw new IllegalArgumentException("catalog category is outside the Domain Pack");
            }
            String brand = boundedText(spu, "brand", 120);
            List<String> tags = strings(spu, "tags", 100);
            for (JsonNode sku : array(spu, "skus", 200)) {
                String skuId = boundedText(sku, "sku_id", 128);
                if (!seenSkuIds.add(skuId)) throw new IllegalArgumentException("duplicate catalog SKU id");
                String title = boundedText(sku, "title", 300);
                List<String> connectors = strings(sku, "connectors", 100);
                List<String> protocols = strings(sku, "protocols", 100);
                for (JsonNode offer : array(sku, "offers", 100)) {
                    String offerId = boundedText(offer, "offer_id", 128);
                    if (!seenOfferIds.add(offerId)) throw new IllegalArgumentException("duplicate catalog offer id");
                    int stock = nonNegativeInteger(offer, "stock");
                    if (stock <= 0) continue;
                    BigDecimal price = nonNegativeDecimal(offer, "price");
                    boolean sponsored = offer.path("sponsored").asBoolean(false);
                    double bid = offer.path("ad_bid").asDouble(0);
                    double quality = offer.path("ad_quality").asDouble(derivedQuality(spuId));
                    String productId = skuId + "@" + offerId;
                    products.add(new Product(
                            productId,
                            title,
                            category,
                            brand,
                            price,
                            tags,
                            clamp(quality),
                            derivedPopularity(spuId),
                            sponsored,
                            clamp(bid / 3.0),
                            compatibilityGroup(connectors, protocols),
                            stock,
                            source.source(),
                            source.sourceVersion(),
                            source.providerId()));
                    if (metadata.containsKey(productId)) {
                        throw new IllegalArgumentException("duplicate catalog product id");
                    }
                    metadata.put(productId, new CatalogItemMetadata(
                            spuId,
                            offerId,
                            stock,
                            connectors,
                            protocols,
                            source.source(),
                            source.sourceVersion(),
                            source.providerId()));
                }
            }
        }
        if (products.isEmpty()) throw new IllegalArgumentException("catalog contains no sellable products");
        products.sort(Comparator.comparing(Product::id));
        return new CatalogParse(version, List.copyOf(products), Map.copyOf(metadata));
    }

    private CatalogParse applyQuotes(CatalogParse catalog, JsonNode root) {
        Map<String, JsonNode> quotes = new HashMap<>();
        for (JsonNode quote : array(root, "quotes", 100)) {
            String offerId = boundedText(quote, "offer_id", 128);
            String status = boundedText(quote, "status", 32);
            if (!status.equals("active") && !status.equals("unavailable")) {
                throw new IllegalArgumentException("unsupported pricing status");
            }
            if (quotes.putIfAbsent(offerId, quote) != null) {
                throw new IllegalArgumentException("duplicate pricing quote");
            }
        }
        Set<String> requestedOfferIds = catalog.metadata().values().stream()
                .map(CatalogItemMetadata::offerId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!quotes.keySet().equals(requestedOfferIds)) {
            throw new IllegalArgumentException("pricing response must cover exactly every requested offer");
        }

        List<Product> products = new ArrayList<>();
        Map<String, CatalogItemMetadata> metadata = new LinkedHashMap<>();
        for (Product product : catalog.products()) {
            CatalogItemMetadata item = catalog.metadata().get(product.id());
            JsonNode quote = quotes.get(item.offerId());
            if (quote == null || !quote.path("status").asText().equals("active")) continue;
            BigDecimal amount = nonNegativeDecimal(quote, "amount");
            int stock = nonNegativeInteger(quote, "stock");
            if (stock <= 0) throw new IllegalArgumentException("active quote must have stock");
            products.add(new Product(
                    product.id(), product.name(), product.category(), product.brand(), amount,
                    product.tags(), product.qualityScore(), product.popularityScore(),
                    product.sponsored(), product.bidScore(), product.compatibilityGroup(),
                    stock, "remote_provider", text(root.path("data_source"), "source_version"),
                    remoteProviderId));
            metadata.put(product.id(), new CatalogItemMetadata(
                    item.spuId(), item.offerId(), stock, item.connectors(), item.protocols(),
                    "remote_provider", text(root.path("data_source"), "source_version"), remoteProviderId));
        }
        if (products.isEmpty()) throw new IllegalArgumentException("pricing removed every product");
        return new CatalogParse(catalog.version(), List.copyOf(products), Map.copyOf(metadata));
    }

    private ReviewParse parseReviews(JsonNode root, DataSourceMetadata source) {
        String version = safeVersion(text(root, "review_snapshot_version"), "review version");
        if (!source.sourceVersion().equals(version)) {
            throw new IllegalArgumentException("review source version mismatch");
        }
        Map<String, ProductEvidence> evidence = new LinkedHashMap<>();
        for (JsonNode product : array(root, "products", 2_000)) {
            String productId = boundedText(product, "product_id", 128);
            List<ReviewAspect> aspects = new ArrayList<>();
            for (JsonNode aspect : array(product, "aspects", 100)) {
                aspects.add(new ReviewAspect(
                        boundedText(aspect, "aspect", 120),
                        finiteBetween(aspect, "sentiment", -1, 1),
                        nonNegativeInteger(aspect, "mention_count"),
                        finiteBetween(aspect, "confidence", 0, 1),
                        boundedText(aspect, "summary", 1_000)));
            }
            if (evidence.putIfAbsent(productId,
                    new ProductEvidence(nonNegativeInteger(product, "sample_size"), aspects)) != null) {
                throw new IllegalArgumentException("duplicate review product id");
            }
        }
        return new ReviewParse(version, Map.copyOf(evidence), source);
    }

    private CompatibilityParse parseCompatibility(CommerceDomainPack pack, JsonNode root) {
        String version = safeVersion(text(root, "graph_version"), "compatibility version");
        Set<String> categories = pack.categories().stream()
                .map(CommerceDomainPack.CategoryDefinition::id)
                .collect(java.util.stream.Collectors.toSet());
        List<CompatibilityRule> rules = new ArrayList<>();
        for (JsonNode rule : array(root, "rules", 100)) {
            CompatibilityRule parsed = new CompatibilityRule(
                    boundedText(rule, "rule_id", 128),
                    boundedText(rule, "primary_category", 64),
                    boundedText(rule, "accessory_category", 64),
                    strings(rule, "required_shared_connectors", 100),
                    strings(rule, "required_shared_protocols", 100));
            if (!categories.contains(parsed.primaryCategory())
                    || !categories.contains(parsed.accessoryCategory())) {
                throw new IllegalArgumentException("compatibility rule references an unknown category");
            }
            rules.add(parsed);
        }
        return new CompatibilityParse(version, List.copyOf(rules));
    }

    private static void validateReviewPartition(
            JsonNode root,
            List<String> requestedIds,
            Set<String> foundIds
    ) {
        Set<String> requested = new LinkedHashSet<>(requestedIds);
        Set<String> missing = new LinkedHashSet<>(strings(root, "missing_product_ids", 100));
        Set<String> found = new LinkedHashSet<>(foundIds);
        Set<String> covered = new LinkedHashSet<>(found);
        covered.addAll(missing);
        Set<String> overlap = new LinkedHashSet<>(found);
        overlap.retainAll(missing);
        if (!requested.containsAll(found) || !requested.containsAll(missing)
                || !overlap.isEmpty() || !covered.equals(requested)) {
            throw new IllegalArgumentException(
                    "review response must partition every requested product id");
        }
    }

    private static void validateRequiredInventory(
            CommerceDomainPack pack,
            RetailDataSnapshot snapshot
    ) {
        Set<String> available = snapshot.products().stream()
                .map(Product::category)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>();
        missing.add(pack.defaultCategory());
        missing.add(pack.primaryCategory());
        missing.addAll(pack.defaultBundleCategories());
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Domain Pack default decision path has no sellable inventory: "
                            + pack.packId() + " " + missing);
        }
    }
    private void markLocal(RetailDataSnapshot snapshot, boolean fallback, String errorCode) {
        Map<String, String> versions = Map.of(
                "catalog", snapshot.catalogVersion(),
                "reviews", snapshot.reviewVersion(),
                "pricing", snapshot.sources().get("pricingVersion"));
        versions.forEach((name, version) -> {
            RetailSourceState state = states.get(name);
            if (!fallback) state.requestStarted();
            DataSourceMetadata metadata = localMetadata(version, snapshot.domainPackId());
            if (fallback) state.fellBack(metadata, errorCode); else state.succeeded(metadata);
        });
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
                if (encoded.length > 256 * 1024) throw new ProviderException("provider_request_too_large");
                request.header("content-type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(encoded));
            }
            HttpResponse<InputStream> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new ProviderException("provider_http_error");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                response.body().close();
                throw new ProviderException("provider_invalid_content_type");
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(properties.maxResponseBytes() + 1);
            }
            if (bytes.length > properties.maxResponseBytes()) {
                throw new ProviderException("provider_response_too_large");
            }
            JsonNode result;
            try {
                result = providerMapper.readTree(bytes);
            } catch (JsonProcessingException error) {
                throw new ProviderException("provider_invalid_response");
            }
            if (!result.isObject()) throw new ProviderException("provider_invalid_response");
            return result;
        } catch (java.net.http.HttpTimeoutException error) {
            throw new ProviderException("provider_timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProviderException("provider_interrupted");
        } catch (ProviderException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            throw new ProviderException("provider_network_error");
        }
    }

    private JsonNode read(String resource) throws IOException {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return mapper.readTree(input);
        }
    }

    private DataSourceMetadata remoteMetadata(JsonNode source, String expectedVersion) {
        String kind = boundedText(source, "source", 32);
        String version = safeVersion(text(source, "source_version"), "source version");
        String provider = boundedText(source, "provider_id", 64);
        if (!kind.equals("remote_provider") || !version.equals(expectedVersion)
                || !provider.equals(remoteProviderId)) {
            throw new IllegalArgumentException("remote source metadata mismatch");
        }
        return new DataSourceMetadata(kind, version, provider);
    }

    private DataSourceMetadata remoteMetadata(String version) {
        return new DataSourceMetadata("remote_provider", version, remoteProviderId);
    }

    private static DataSourceMetadata localMetadata(String version, String providerId) {
        return new DataSourceMetadata("local_snapshot", version, providerId);
    }

    private static void validateRemoteUri(RetailProviderProperties properties) {
        if (!properties.enabled()) return;
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
            throw new IllegalArgumentException("non-loopback HTTP provider requires explicit opt-in");
        }
    }

    private static String providerId(String baseUrl) {
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
            StringBuilder value = new StringBuilder("retail-");
            for (int index = 0; index < 8; index++) value.append(String.format("%02x", digest[index]));
            String result = value.toString();
            if (!PROVIDER_ID.matcher(result).matches()) throw new IllegalStateException("invalid provider id");
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("unable to fingerprint retail provider", error);
        }
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode array(JsonNode root, String field, int maximum) {
        JsonNode value = root.path(field);
        if (!value.isArray() || value.size() > maximum) {
            throw new IllegalArgumentException(field + " must be a bounded array");
        }
        return value;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank() || !value.asText().equals(value.asText().trim())) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static String boundedText(JsonNode root, String field, int maximum) {
        String value = text(root, field);
        if (value.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return value;
    }

    private static String safeVersion(String value, String field) {
        if (!SAFE_VERSION.matcher(value).matches()) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static List<String> strings(JsonNode root, String field, int maximum) {
        JsonNode raw = root.path(field);
        if (!raw.isArray() || raw.size() > maximum) throw new IllegalArgumentException(field + " is invalid");
        List<String> values = new ArrayList<>();
        raw.forEach(item -> {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException(field + " is invalid");
            values.add(item.asText());
        });
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " contains duplicates");
        }
        return List.copyOf(values);
    }

    private static BigDecimal nonNegativeDecimal(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isNumber() || value.decimalValue().signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value.decimalValue();
    }

    private static int nonNegativeInteger(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || value.asInt() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return value.asInt();
    }

    private static double finiteBetween(JsonNode root, String field, double minimum, double maximum) {
        double value = root.path(field).asDouble(Double.NaN);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is outside its accepted range");
        }
        return value;
    }

    private static double derivedQuality(String key) {
        return 0.78 + Math.floorMod(key.hashCode(), 18) / 100.0;
    }

    private static double derivedPopularity(String key) {
        return 0.70 + Math.floorMod(key.hashCode() * 31, 25) / 100.0;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String compatibilityGroup(List<String> connectors, List<String> protocols) {
        if (connectors.contains("en417-thread")) return "en417";
        if (connectors.contains("bayonet")) return "bayonet";
        if (protocols.contains("bluetooth-aac")) return "bluetooth-aac";
        if (connectors.contains("usb-c")) return "usb-c";
        return "universal";
    }

    private record CatalogParse(
            String version,
            List<Product> products,
            Map<String, CatalogItemMetadata> metadata
    ) {
    }

    private record ReviewParse(
            String version,
            Map<String, ProductEvidence> evidence,
            DataSourceMetadata source
    ) {
    }

    private record CompatibilityParse(String version, List<CompatibilityRule> rules) {
    }

    public record RevalidatedSelection(
            List<Product> items,
            BigDecimal totalPrice,
            String catalogVersion,
            String pricingVersion,
            String providerId
    ) {
        public RevalidatedSelection {
            items = List.copyOf(items);
        }
    }
    public static final class ProviderException extends RuntimeException {
        private final String code;

        ProviderException(String code) {
            super("retail data provider unavailable");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
