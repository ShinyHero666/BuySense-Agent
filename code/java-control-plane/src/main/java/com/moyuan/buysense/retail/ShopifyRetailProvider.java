package com.moyuan.buysense.retail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.buysense.platform.CommerceDomainPack;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShopifyRetailProvider implements RetailProvider {
    private static final int MAX_PRODUCTS = 2_000;
    private static final int PAGE_SIZE = 5;
    private static final int VARIANT_PAGE_SIZE = 25;
    private static final Pattern PRODUCT_GID = Pattern.compile(
            "^gid://shopify/Product/([1-9][0-9]*)$");
    private static final Pattern VARIANT_GID = Pattern.compile(
            "^gid://shopify/ProductVariant/([1-9][0-9]*)$");
    private static final Set<String> NON_FALLBACKABLE_ERRORS = Set.of(
            "provider_access_forbidden",
            "provider_api_version_mismatch",
            "provider_authentication_failed",
            "provider_insufficient_scope",
            "provider_query_cost_exceeded",
            "provider_request_rejected",
            "provider_shop_inactive",
            "provider_write_scope_forbidden");

    private static final String CATALOG_QUERY = ShopifyGraphQlClient.CATALOG_QUERY;
    private static final String REVIEWS_QUERY = ShopifyGraphQlClient.REVIEWS_QUERY;
    private static final String PRICING_QUERY = ShopifyGraphQlClient.PRICING_QUERY;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final ShopifyProviderProperties properties;
    private final ShopifyGraphQlClient client;
    private final Clock clock;
    private final String providerId;
    private final String shopHash;
    private final Map<String, CatalogState> catalogs = new ConcurrentHashMap<>();

    ShopifyRetailProvider(ObjectMapper mapper, ShopifyProviderProperties properties) {
        this(mapper, properties, endpoint(properties), Clock.systemUTC());
    }

    ShopifyRetailProvider(
            ObjectMapper mapper,
            ShopifyProviderProperties properties,
            URI endpoint,
            Clock clock
    ) {
        if (!properties.enabled()) {
            throw new IllegalArgumentException("Shopify provider is disabled");
        }
        this.mapper = mapper;
        this.strictMapper = mapper.copy().enable(
                com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        this.clock = clock;
        this.providerId = "shopify-" + digestHex(canonicalEndpoint(properties), 8);
        this.shopHash = digestHex(properties.storeDomain(), 6);
        this.client = new ShopifyGraphQlClient(mapper, properties, endpoint);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String mode() {
        return "shopify";
    }

    @Override
    public JsonNode catalog(CommerceDomainPack pack) {
        return catalogState(pack).catalog().deepCopy();
    }

    @Override
    public JsonNode reviews(CommerceDomainPack pack, List<String> productIds) {
        validateIds(productIds, "product ids");
        CatalogState catalog = catalogState(pack);
        try {
            Map<String, String> known = new LinkedHashMap<>();
            ArrayNode gids = mapper.createArrayNode();
            for (String productId : productIds) {
                String gid = catalog.productGids().get(productId);
                known.put(productId, gid);
                if (gid != null) gids.add(gid);
            }
            JsonNode data = client.execute(REVIEWS_QUERY, Map.of("ids", gids));
            return reviewResponse(productIds, known, data);
        } catch (RetailDataGateway.ProviderException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new RetailDataGateway.ProviderException("provider_invalid_response");
        }
    }

    @Override
    public JsonNode pricing(CommerceDomainPack pack, List<String> offerIds) {
        validateIds(offerIds, "offer ids");
        CatalogState catalog = catalogState(pack);
        try {
            Map<String, String> known = new LinkedHashMap<>();
            ArrayNode gids = mapper.createArrayNode();
            for (String offerId : offerIds) {
                String gid = catalog.variantGids().get(offerId);
                known.put(offerId, gid);
                if (gid != null) gids.add(gid);
            }
            JsonNode data = client.execute(PRICING_QUERY, Map.of("ids", gids));
            return pricingResponse(offerIds, known, data);
        } catch (RetailDataGateway.ProviderException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new RetailDataGateway.ProviderException("provider_invalid_response");
        }
    }

    @Override
    public void beforeConfirmation(String domainPackId) {
        catalogs.remove(domainPackId);
    }

    @Override
    public boolean allowsFallback(String errorCode) {
        return !NON_FALLBACKABLE_ERRORS.contains(errorCode);
    }

    private CatalogState catalogState(CommerceDomainPack pack) {
        Instant now = clock.instant();
        CatalogState current = catalogs.get(pack.packId());
        if (current != null && current.expiresAt().isAfter(now)) return current;
        try {
            return catalogs.compute(pack.packId(), (packId, cached) -> {
                Instant refreshedAt = clock.instant();
                if (cached != null && cached.expiresAt().isAfter(refreshedAt)) return cached;
                return buildCatalog(pack, refreshedAt);
            });
        } catch (RetailDataGateway.ProviderException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new RetailDataGateway.ProviderException("provider_invalid_response");
        }
    }

    private CatalogState buildCatalog(CommerceDomainPack pack, Instant generatedAt) {
        List<JsonNode> nodes = loadProductNodes(pack);
        ArrayNode spus = mapper.createArrayNode();
        Map<String, String> productGids = new LinkedHashMap<>();
        Map<String, String> variantGids = new LinkedHashMap<>();
        Set<String> seenVariantGids = new LinkedHashSet<>();
        Set<String> allowedCategories = pack.categories().stream()
                .map(CommerceDomainPack.CategoryDefinition::id)
                .collect(java.util.stream.Collectors.toSet());
        String validUntil = generatedAt.plusSeconds(24 * 60 * 60).toString();

        for (int productIndex = 0; productIndex < nodes.size(); productIndex++) {
            JsonNode product = nodes.get(productIndex);
            String productGid = gid(product.path("id"), PRODUCT_GID, "product.id");
            String productNumber = numericGid(productGid, PRODUCT_GID);
            ObjectNode metadata = productMetadata(product, pack);
            timestamp(product.path("updatedAt"), "product.updatedAt");
            if (booleanValue(product, "requiresSellingPlan")
                    || !booleanValue(product, "publishedInContext")) {
                continue;
            }

            JsonNode variants = object(product.path("variants"), "product.variants");
            JsonNode pageInfo = object(variants.path("pageInfo"), "product.variants.pageInfo");
            if (!pageInfo.path("hasNextPage").isBoolean()
                    || pageInfo.path("hasNextPage").booleanValue()) {
                throw new IllegalArgumentException(
                        "Shopify product has more than " + VARIANT_PAGE_SIZE + " variants");
            }
            JsonNode variantNodes = variants.path("nodes");
            if (!variantNodes.isArray() || variantNodes.isEmpty()
                    || variantNodes.size() > VARIANT_PAGE_SIZE) {
                throw new IllegalArgumentException("Shopify product variants are invalid");
            }

            String category = boundedText(metadata, "category", 64);
            if (!allowedCategories.contains(category)) {
                throw new IllegalArgumentException("Shopify category is outside the Domain Pack");
            }
            String brand = metadata.has("brand")
                    ? boundedText(metadata, "brand", 120)
                    : boundedText(product, "vendor", 120);
            ArrayNode tags = metadata.has("tags")
                    ? stringArray(metadata.path("tags"), "product metadata tags", 100)
                    : mapper.createArrayNode();
            String spuId = "spu-shopify-" + shopHash + "-" + productNumber;
            ArrayNode skus = mapper.createArrayNode();

            for (int variantIndex = 0; variantIndex < variantNodes.size(); variantIndex++) {
                JsonNode variant = object(
                        variantNodes.get(variantIndex), "product.variants.nodes[]");
                String variantGid = gid(variant.path("id"), VARIANT_GID, "variant.id");
                if (!seenVariantGids.add(variantGid)) {
                    throw new IllegalArgumentException("duplicate Shopify variant GID");
                }
                if (booleanValue(variant, "requiresComponents")
                        || !booleanValue(variant, "availableForSale")) {
                    continue;
                }
                timestamp(variant.path("updatedAt"), "variant.updatedAt");
                PriceStock priceStock = priceAndStock(variant, "variant");
                VariantMetadata details = variantMetadata(variant.path("sarVariant"));
                String variantNumber = numericGid(variantGid, VARIANT_GID);
                String skuId = "sku-shopify-" + shopHash + "-" + variantNumber;
                String offerId = "offer-shopify-" + shopHash + "-" + variantNumber;

                ObjectNode sku = skus.addObject();
                sku.put("sku_id", skuId);
                sku.put("title", boundedText(variant, "title", 500));
                sku.put("ecosystem", details.ecosystem());
                sku.set("connectors", mapper.valueToTree(details.connectors()));
                sku.set("protocols", mapper.valueToTree(details.protocols()));
                if (details.maxPowerWatts() != null) {
                    sku.put("max_power_watts", details.maxPowerWatts());
                }
                ObjectNode offer = sku.putArray("offers").addObject();
                offer.put("offer_id", offerId);
                offer.put("seller_id", details.sellerId() == null
                        ? "shopify-" + shopHash : details.sellerId());
                offer.put("price", priceStock.amount());
                offer.put("currency", "CNY");
                offer.put("stock", priceStock.stock());
                offer.put("sponsored", details.sponsored());
                offer.put("valid_until", validUntil);
                offer.put("ad_bid", details.adBid());
                offer.put("ad_quality", details.adQuality());
                variantGids.put(offerId, variantGid);
            }
            if (skus.isEmpty()) continue;

            ObjectNode spu = spus.addObject();
            spu.put("spu_id", spuId);
            spu.put("title", boundedText(product, "title", 500));
            spu.put("category", category);
            spu.put("brand", brand);
            spu.set("tags", tags);
            spu.set("skus", skus);
            productGids.put(spuId, productGid);
        }
        if (spus.isEmpty()) {
            throw new IllegalArgumentException(
                    "Shopify catalog has no products for " + pack.packId());
        }

        ObjectNode versionInput = mapper.createObjectNode();
        versionInput.put("pack", pack.packId());
        versionInput.set("products", spus);
        ArrayNode updates = versionInput.putArray("updates");
        nodes.forEach(node -> updates.add(node.path("updatedAt").asText()));
        String version = sourceVersion("catalog", versionInput);
        ObjectNode catalog = mapper.createObjectNode();
        catalog.put("catalog_version", version);
        catalog.put("quote_version", version);
        catalog.put("generated_at", generatedAt.toString());
        catalog.set("spus", spus);
        catalog.set("data_source", metadata(version));
        return new CatalogState(
                catalog,
                Map.copyOf(productGids),
                Map.copyOf(variantGids),
                generatedAt.plus(properties.catalogCacheTtl()));
    }

    private List<JsonNode> loadProductNodes(CommerceDomainPack pack) {
        List<JsonNode> result = new ArrayList<>();
        Set<String> productGids = new LinkedHashSet<>();
        Set<String> cursors = new LinkedHashSet<>();
        String cursor = null;
        String search = "status:active published_status:published "
                + "tag:moyuan-domain-pack-" + pack.packId();
        while (true) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("first", PAGE_SIZE);
            variables.put("after", cursor);
            variables.put("query", search);
            JsonNode data = client.execute(CATALOG_QUERY, variables);
            validateReadProductsScope(data.path("currentAppInstallation"));
            JsonNode products = object(data.path("products"), "data.products");
            JsonNode pageNodes = products.path("nodes");
            if (!pageNodes.isArray() || pageNodes.size() > PAGE_SIZE) {
                throw new RetailDataGateway.ProviderException("provider_invalid_response");
            }
            for (JsonNode raw : pageNodes) {
                JsonNode node = object(raw, "data.products.nodes[]");
                String gid = gid(node.path("id"), PRODUCT_GID, "product.id");
                if (!productGids.add(gid)) {
                    throw new RetailDataGateway.ProviderException("provider_invalid_response");
                }
                result.add(node);
            }
            if (result.size() > MAX_PRODUCTS) {
                throw new RetailDataGateway.ProviderException("provider_catalog_too_large");
            }
            JsonNode pageInfo = object(products.path("pageInfo"), "data.products.pageInfo");
            JsonNode hasNext = pageInfo.path("hasNextPage");
            if (!hasNext.isBoolean()) {
                throw new RetailDataGateway.ProviderException("provider_invalid_response");
            }
            if (!hasNext.booleanValue()) break;
            JsonNode endCursor = pageInfo.path("endCursor");
            if (!endCursor.isTextual() || endCursor.textValue().isBlank()
                    || !cursors.add(endCursor.textValue())) {
                throw new RetailDataGateway.ProviderException("provider_invalid_response");
            }
            cursor = endCursor.textValue();
        }
        return List.copyOf(result);
    }

    private ObjectNode reviewResponse(
            List<String> requestedIds,
            Map<String, String> known,
            JsonNode data
    ) {
        JsonNode rawNodes = data.path("nodes");
        if (!rawNodes.isArray() || rawNodes.size() > requestedIds.size()) {
            throw new IllegalArgumentException("Shopify review nodes are invalid");
        }
        Set<String> expected = new LinkedHashSet<>();
        known.values().stream().filter(java.util.Objects::nonNull).forEach(expected::add);
        Map<String, JsonNode> byGid = new HashMap<>();
        for (JsonNode raw : rawNodes) {
            if (raw.isNull()) continue;
            JsonNode node = object(raw, "reviews.nodes[]");
            if (!"Product".equals(node.path("__typename").asText())) continue;
            String productGid = gid(node.path("id"), PRODUCT_GID, "review product id");
            if (!expected.contains(productGid) || byGid.putIfAbsent(productGid, node) != null) {
                throw new IllegalArgumentException("unexpected Shopify review product id");
            }
            timestamp(node.path("updatedAt"), "review product updatedAt");
        }

        String version = sourceVersion("reviews", rawNodes);
        ObjectNode response = mapper.createObjectNode();
        response.put("review_snapshot_version", version);
        response.set("data_source", metadata(version));
        ArrayNode products = response.putArray("products");
        ArrayNode missing = response.putArray("missing_product_ids");
        for (String productId : requestedIds) {
            JsonNode node = byGid.get(known.get(productId));
            if (node == null || (node.path("reviewsRating").isMissingNode()
                    && node.path("reviewsRatingCount").isMissingNode())) {
                missing.add(productId);
                continue;
            }
            if (!node.hasNonNull("reviewsRating") || !node.hasNonNull("reviewsRatingCount")) {
                throw new IllegalArgumentException("Shopify review metafields are incomplete");
            }
            double rating = rating(node.path("reviewsRating"));
            int count = ratingCount(node.path("reviewsRatingCount"));
            double sentiment = Math.max(-1, Math.min(1, (rating - 3) / 2));
            ObjectNode product = products.addObject();
            product.put("product_id", productId);
            product.put("sample_size", count);
            ObjectNode aspect = product.putArray("aspects").addObject();
            aspect.put("aspect", "overall_rating");
            aspect.put("sentiment", sentiment);
            aspect.put("mention_count", count);
            aspect.put("confidence", 1.0);
            aspect.put("summary", "Average customer rating "
                    + BigDecimal.valueOf(rating).stripTrailingZeros().toPlainString()
                    + "/5 (" + count + " reviews)");
        }
        return response;
    }

    private ObjectNode pricingResponse(
            List<String> offerIds,
            Map<String, String> known,
            JsonNode data
    ) {
        JsonNode rawNodes = data.path("nodes");
        if (!rawNodes.isArray() || rawNodes.size() > offerIds.size()) {
            throw new IllegalArgumentException("Shopify pricing nodes are invalid");
        }
        Set<String> expected = new LinkedHashSet<>();
        known.values().stream().filter(java.util.Objects::nonNull).forEach(expected::add);
        Map<String, JsonNode> byGid = new HashMap<>();
        for (JsonNode raw : rawNodes) {
            if (raw.isNull()) continue;
            JsonNode node = object(raw, "pricing.nodes[]");
            if (!"ProductVariant".equals(node.path("__typename").asText())) continue;
            String variantGid = gid(node.path("id"), VARIANT_GID, "pricing variant id");
            if (!expected.contains(variantGid) || byGid.putIfAbsent(variantGid, node) != null) {
                throw new IllegalArgumentException("unexpected Shopify pricing variant id");
            }
            timestamp(node.path("updatedAt"), "pricing variant updatedAt");
        }

        Instant issuedAt = clock.instant();
        String version = sourceVersion("pricing", rawNodes);
        ObjectNode response = mapper.createObjectNode();
        response.put("quote_batch_id", "shopify-quote-" + digestHex(
                String.join("|", offerIds) + issuedAt, 8));
        response.put("quote_version", version);
        response.put("issued_at", issuedAt.toString());
        response.set("data_source", metadata(version));
        ArrayNode quotes = response.putArray("quotes");
        for (String offerId : offerIds) {
            JsonNode node = byGid.get(known.get(offerId));
            if (node == null) {
                unavailableQuote(quotes, offerId, issuedAt, "offer_not_found");
                continue;
            }
            JsonNode product = object(node.path("product"), "pricing variant product");
            boolean requiresComponents = booleanValue(node, "requiresComponents");
            boolean available = booleanValue(node, "availableForSale");
            boolean published = booleanValue(product, "publishedInContext");
            if (requiresComponents || !available || !published) {
                unavailableQuote(quotes, offerId, issuedAt,
                        !published ? "product_not_published_in_cn" : "offer_not_sellable");
                continue;
            }
            PriceStock current = priceAndStock(node, "pricing variant");
            if (current.stock() <= 0) {
                unavailableQuote(quotes, offerId, issuedAt, "out_of_stock");
                continue;
            }
            ObjectNode quote = quotes.addObject();
            quote.put("offer_id", offerId);
            quote.put("status", "active");
            quote.put("amount", current.amount());
            quote.put("currency", "CNY");
            quote.put("stock", current.stock());
            quote.put("valid_until", issuedAt.plusSeconds(5 * 60).toString());
            quote.put("reason", "shopify_contextual_price");
        }
        return response;
    }

    private void unavailableQuote(
            ArrayNode quotes,
            String offerId,
            Instant issuedAt,
            String reason
    ) {
        ObjectNode quote = quotes.addObject();
        quote.put("offer_id", offerId);
        quote.put("status", "unavailable");
        quote.putNull("amount");
        quote.put("currency", "CNY");
        quote.put("stock", 0);
        quote.put("valid_until", issuedAt.toString());
        quote.put("reason", reason);
    }

    private ObjectNode productMetadata(JsonNode product, CommerceDomainPack pack) {
        List<String> tags = strings(product.path("tags"), "product.tags", 250);
        String marker = "moyuan-domain-pack-" + pack.packId();
        List<String> markers = tags.stream()
                .filter(tag -> tag.startsWith("moyuan-domain-pack-"))
                .toList();
        if (!markers.equals(List.of(marker))) {
            throw new IllegalArgumentException(
                    "Shopify product must have exactly one matching Domain Pack tag");
        }
        ObjectNode metadata = metafieldJson(product.path("sarProduct"), "product.sarProduct");
        rejectUnknown(metadata, Set.of("domain_pack_id", "category", "brand", "tags"));
        if (!pack.packId().equals(boundedText(metadata, "domain_pack_id", 64))) {
            throw new IllegalArgumentException("Shopify tag and product metadata disagree");
        }
        boundedText(metadata, "category", 64);
        if (metadata.has("brand")) boundedText(metadata, "brand", 120);
        if (metadata.has("tags")) stringArray(metadata.path("tags"), "metadata.tags", 100);
        return metadata;
    }

    private VariantMetadata variantMetadata(JsonNode metafield) {
        ObjectNode metadata = metafieldJson(metafield, "variant.sarVariant");
        rejectUnknown(metadata, Set.of(
                "ecosystem", "connectors", "protocols", "max_power_watts",
                "seller_id", "sponsored", "ad_bid", "ad_quality"));
        String ecosystem = boundedText(metadata, "ecosystem", 32);
        if (!Set.of("ios", "android", "universal").contains(ecosystem)) {
            throw new IllegalArgumentException("unsupported Shopify variant ecosystem");
        }
        List<String> connectors = strings(metadata.path("connectors"), "connectors", 100);
        List<String> protocols = strings(metadata.path("protocols"), "protocols", 100);
        Integer maxPower = null;
        if (metadata.has("max_power_watts")) {
            JsonNode value = metadata.path("max_power_watts");
            if (!value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() <= 0) {
                throw new IllegalArgumentException("max_power_watts must be positive");
            }
            maxPower = value.intValue();
        }
        String seller = metadata.has("seller_id")
                ? boundedText(metadata, "seller_id", 128) : null;
        boolean sponsored = false;
        if (metadata.has("sponsored")) {
            if (!metadata.path("sponsored").isBoolean()) {
                throw new IllegalArgumentException("sponsored must be boolean");
            }
            sponsored = metadata.path("sponsored").booleanValue();
        }
        double bid = finiteNumber(metadata.path("ad_bid"), 0, Double.MAX_VALUE, 0);
        double quality = finiteNumber(metadata.path("ad_quality"), 0, 1, 0);
        return new VariantMetadata(
                ecosystem, connectors, protocols, maxPower, seller, sponsored, bid, quality);
    }

    private ObjectNode metafieldJson(JsonNode metafield, String field) {
        JsonNode value = object(metafield, field);
        if (!"json".equals(value.path("type").asText())) {
            throw new IllegalArgumentException(field + " must have type json");
        }
        JsonNode json = value.get("jsonValue");
        if (json == null || json.isNull()) {
            JsonNode serialized = value.path("value");
            if (!serialized.isTextual()
                    || serialized.textValue().getBytes(StandardCharsets.UTF_8).length > 64 * 1_024) {
                throw new IllegalArgumentException(field + " must contain bounded JSON");
            }
            try {
                json = strictMapper.readTree(serialized.textValue());
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException(field + " contains invalid JSON", error);
            }
        }
        if (!json.isObject()) throw new IllegalArgumentException(field + " must contain an object");
        return (ObjectNode) json;
    }

    private double rating(JsonNode metafield) {
        JsonNode value = object(metafield, "reviews.rating");
        if (!"rating".equals(value.path("type").asText())) {
            throw new IllegalArgumentException("reviews.rating must have type rating");
        }
        JsonNode json = value.get("jsonValue");
        if (json == null || json.isNull()) {
            if (!value.path("value").isTextual()) {
                throw new IllegalArgumentException("reviews.rating must contain JSON");
            }
            try {
                json = strictMapper.readTree(value.path("value").textValue());
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("reviews.rating contains invalid JSON", error);
            }
        }
        JsonNode rating = object(json, "reviews.rating.jsonValue");
        double number = ratingNumber(rating.path("value"), 1, 5);
        double minimum = ratingNumber(rating.path("scale_min"), 1, 1);
        double maximum = ratingNumber(rating.path("scale_max"), 5, 5);
        if (!Double.isFinite(number) || minimum != 1 || maximum != 5) {
            throw new IllegalArgumentException("reviews.rating must use the 1-5 scale");
        }
        return number;
    }

    private int ratingCount(JsonNode metafield) {
        JsonNode value = object(metafield, "reviews.rating_count");
        if (!"number_integer".equals(value.path("type").asText())) {
            throw new IllegalArgumentException(
                    "reviews.rating_count must have type number_integer");
        }
        String raw = value.path("value").asText("");
        if (!raw.matches("[0-9]+")) {
            throw new IllegalArgumentException("reviews.rating_count must be non-negative");
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("reviews.rating_count is too large", error);
        }
    }

    private PriceStock priceAndStock(JsonNode node, String field) {
        JsonNode pricing = object(node.path("contextualPricing"), field + ".contextualPricing");
        JsonNode price = object(pricing.path("price"), field + ".contextualPricing.price");
        if (!"CNY".equals(price.path("currencyCode").asText())) {
            throw new IllegalArgumentException(field + " price must use CNY");
        }
        JsonNode amount = price.path("amount");
        if (!amount.isTextual() || amount.textValue().length() > 64) {
            throw new IllegalArgumentException(field + " amount must be a decimal string");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(amount.textValue());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " amount is invalid", error);
        }
        if (parsed.signum() < 0) throw new IllegalArgumentException(field + " amount is negative");
        JsonNode stock = node.path("sellableOnlineQuantity");
        if (!stock.isIntegralNumber() || !stock.canConvertToInt()
                || stock.intValue() < 0) {
            throw new IllegalArgumentException(field + " stock must be non-negative");
        }
        return new PriceStock(parsed, stock.intValue());
    }

    private void validateReadProductsScope(JsonNode installation) {
        JsonNode root = object(installation, "data.currentAppInstallation");
        JsonNode scopes = root.path("accessScopes");
        if (!scopes.isArray() || scopes.size() > 512) {
            throw new RetailDataGateway.ProviderException("provider_invalid_response");
        }
        Set<String> handles = new LinkedHashSet<>();
        for (JsonNode raw : scopes) {
            handles.add(boundedText(object(raw, "access scope"), "handle", 128));
        }
        if (!handles.contains("read_products")) {
            throw new RetailDataGateway.ProviderException("provider_insufficient_scope");
        }
        if (handles.stream().anyMatch(scope -> scope.startsWith("write_"))) {
            throw new RetailDataGateway.ProviderException("provider_write_scope_forbidden");
        }
    }

    private ObjectNode metadata(String version) {
        ObjectNode result = mapper.createObjectNode();
        result.put("source", "remote_provider");
        result.put("source_version", version);
        result.put("provider_id", providerId);
        return result;
    }

    private String sourceVersion(String prefix, JsonNode value) {
        try {
            return "shopify-" + prefix + "-" + properties.apiVersion() + "-"
                    + digestHex(mapper.writeValueAsBytes(value), 8);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("unable to version Shopify response", error);
        }
    }

    private static JsonNode object(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static String boundedText(JsonNode root, String field, int maximum) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.textValue().isBlank()
                || !value.textValue().equals(value.textValue().trim())
                || value.textValue().length() > maximum) {
            throw new IllegalArgumentException(field + " must be bounded text");
        }
        return value.textValue();
    }

    private static boolean booleanValue(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
        return value.booleanValue();
    }

    private static void timestamp(JsonNode value, String field) {
        if (!value.isTextual() || value.textValue().length() > 128) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 timestamp");
        }
        try {
            OffsetDateTime.parse(value.textValue());
        } catch (java.time.format.DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must include a timezone", error);
        }
    }

    private static String gid(JsonNode value, Pattern pattern, String field) {
        if (!value.isTextual() || value.textValue().length() > 128
                || !pattern.matcher(value.textValue()).matches()) {
            throw new IllegalArgumentException(field + " is not a supported Shopify GID");
        }
        return value.textValue();
    }

    private static String numericGid(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("invalid Shopify GID");
        return matcher.group(1);
    }

    private ArrayNode stringArray(JsonNode value, String field, int maximum) {
        List<String> strings = strings(value, field, maximum);
        return mapper.valueToTree(strings);
    }

    private static List<String> strings(JsonNode value, String field, int maximum) {
        if (!value.isArray() || value.size() > maximum) {
            throw new IllegalArgumentException(field + " must be a bounded array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()
                    || !item.textValue().equals(item.textValue().trim())
                    || item.textValue().length() > 120) {
                throw new IllegalArgumentException(field + " contains invalid text");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static void rejectUnknown(ObjectNode value, Set<String> allowed) {
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("unknown Shopify metadata field: " + field);
            }
        });
    }

    private static double ratingNumber(JsonNode value, double minimum, double maximum) {
        double result;
        try {
            result = value.isNumber() ? value.doubleValue()
                    : value.isTextual() ? Double.parseDouble(value.textValue()) : Double.NaN;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("rating number is invalid", error);
        }
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw new IllegalArgumentException("rating number is out of range");
        }
        return result;
    }

    private static double finiteNumber(
            JsonNode value,
            double minimum,
            double maximum,
            double defaultValue
    ) {
        if (value == null || value.isMissingNode() || value.isNull()) return defaultValue;
        if (!value.isNumber()) throw new IllegalArgumentException("number is required");
        double result = value.doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw new IllegalArgumentException("number is out of range");
        }
        return result;
    }

    private static void validateIds(List<String> values, String field) {
        if (values == null || values.size() > 100
                || new LinkedHashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> value == null || value.isBlank()
                || !value.equals(value.trim()) || value.length() > 128)) {
            throw new RetailDataGateway.ProviderException("provider_invalid_request");
        }
    }

    private static URI endpoint(ShopifyProviderProperties properties) {
        return URI.create(canonicalEndpoint(properties));
    }

    private static String canonicalEndpoint(ShopifyProviderProperties properties) {
        return "https://" + properties.storeDomain() + "/admin/api/"
                + properties.apiVersion() + "/graphql.json";
    }

    private static String digestHex(String value, int bytes) {
        return digestHex(value.getBytes(StandardCharsets.UTF_8), bytes);
    }

    private static String digestHex(byte[] value, int bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest, 0, bytes);
        } catch (Exception error) {
            throw new IllegalStateException("unable to calculate SHA-256", error);
        }
    }

    private record PriceStock(BigDecimal amount, int stock) {
    }

    private record VariantMetadata(
            String ecosystem,
            List<String> connectors,
            List<String> protocols,
            Integer maxPowerWatts,
            String sellerId,
            boolean sponsored,
            double adBid,
            double adQuality
    ) {
    }

    private record CatalogState(
            ObjectNode catalog,
            Map<String, String> productGids,
            Map<String, String> variantGids,
            Instant expiresAt
    ) {
    }
}
