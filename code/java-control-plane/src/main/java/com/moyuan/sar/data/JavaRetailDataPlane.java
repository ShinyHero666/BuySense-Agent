package com.moyuan.sar.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.sar.control.DomainPackIndex;
import com.moyuan.sar.control.RetailQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JavaRetailDataPlane {
    private static final Pattern ASCII_TERM = Pattern.compile("[a-z][a-z0-9-]{1,20}|\\d{2,3}w");
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Set<String> DISCOVERY_FIELDS = Set.of(
            "query", "domain_pack_id", "requested_categories", "use_cases",
            "preferred_brands", "primary_product_ids", "max_price", "limit",
            "sponsored_allowed", "identity_id", "session_id",
            "personalization_enabled", "recent_product_ids",
            "excluded_product_ids", "ad_exposure_product_ids");
    private static final Set<String> REVIEW_FIELDS = Set.of("domain_pack_id", "product_ids");
    private static final Set<String> PAIR_FIELDS = Set.of("product_sku_id", "accessory_sku_id");
    private static final Set<String> FUSION_FIELDS = Set.of("domain_pack_id", "channels", "limit");
    private static final Set<String> FUSION_CHANNEL_FIELDS = Set.of("channel", "items", "catalog_version", "quote_version", "data_source");
    private static final Set<String> BUNDLE_FIELDS = Set.of(
            "domain_pack_id", "items", "requested_categories", "intent", "budget_max", "top_n");

    private final ObjectMapper mapper;
    private final Clock clock;
    private final RrfWeightProfile rrfWeightProfile;
    private final String defaultPackId;
    private final Map<String, PackRuntime> packs = new LinkedHashMap<>();

    @Autowired
    public JavaRetailDataPlane(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC(), RrfWeightProfile.CALIBRATED_V1);
    }

    JavaRetailDataPlane(ObjectMapper mapper, Clock clock) {
        this(mapper, clock, RrfWeightProfile.CALIBRATED_V1);
    }

    JavaRetailDataPlane(
            ObjectMapper mapper,
            Clock clock,
            RrfWeightProfile rrfWeightProfile) {
        this.mapper = mapper;
        this.clock = clock;
        this.rrfWeightProfile = rrfWeightProfile;
        DomainPackIndex index = DomainPackIndex.load(mapper);
        this.defaultPackId = index.defaultPackId();
        for (DomainPackIndex.Entry entry : index.packs()) {
            PackRuntime runtime = loadPack(entry.resource());
            if (!entry.packId().equals(runtime.pack.packId)) {
                throw new IllegalStateException(
                        "domain pack index id does not match data-plane asset: " + entry.packId());
            }
            packs.put(entry.packId(), runtime);
        }
    }

    public Map<String, Object> dispatch(String path, JsonNode payload) {
        if ("/api/v2/discovery/search".equals(path) || "/api/java/v2/discovery/search".equals(path)) {
            return discovery(packId(payload), "search", payload);
        }
        if ("/api/v2/discovery/recommend".equals(path) || "/api/java/v2/discovery/recommend".equals(path)) {
            return discovery(packId(payload), "recommendation", payload);
        }
        if ("/api/v2/discovery/ads".equals(path) || "/api/java/v2/discovery/ads".equals(path)) {
            return discovery(packId(payload), "ads", payload);
        }
        if ("/api/v2/evidence/reviews".equals(path) || "/api/java/v2/evidence/reviews".equals(path)) {
            return reviews(packId(payload), payload);
        }
        if ("/api/v2/evidence/compatibility".equals(path)
                || "/api/java/v2/evidence/compatibility".equals(path)) {
            return compatibility(packId(payload), payload);
        }
        if ("/api/v2/pricing/quote".equals(path) || "/api/java/v2/pricing/quote".equals(path)) {
            return quote(packId(payload), payload);
        }
        if ("/api/v2/decision/fuse".equals(path) || "/api/java/v2/decision/fuse".equals(path)) {
            return fuse(packId(payload), payload);
        }
        if ("/api/v2/decision/bundles".equals(path) || "/api/java/v2/decision/bundles".equals(path)) {
            return bundles(packId(payload), payload);
        }
        throw new JavaDataPlaneValidationException("path", "not_found");
    }

    public Map<String, Object> domainPacks() {
        List<Map<String, Object>> metadata = new ArrayList<>();
        packs.values().stream()
                .sorted(Comparator.comparing(runtime -> runtime.pack.packId))
                .forEach(runtime -> metadata.add(runtime.metadata()));
        return Map.of("defaultPackId", defaultPackId, "packs", metadata);
    }

    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "ready", true,
                "service", "moyuan-sar-java-data-plane",
                "components", Map.of(
                        "catalog", "local_snapshot",
                        "reviews", "local_snapshot",
                        "pricing", "local_snapshot"));
    }

    public Map<String, Object> discovery(String packId, String channel, JsonNode payload) {
        PackRuntime runtime = runtime(packId);
        DiscoveryRequest request = parseDiscovery(payload, runtime);
        return switch (channel) {
            case "search" -> search(runtime, request);
            case "recommendation" -> recommend(runtime, request);
            case "ads" -> ads(runtime, request);
            default -> throw new JavaDataPlaneValidationException("channel", "unsupported channel");
        };
    }

    public Map<String, Object> reviews(String packId, JsonNode payload) {
        PackRuntime runtime = runtime(packId);
        ObjectNode object = objectPayload(payload);
        rejectUnknown(object, REVIEW_FIELDS, "request");
        List<String> ids = stringList(object, "product_ids", false, 100);
        List<ObjectNode> products = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            ObjectNode source = runtime.reviews.get(id);
            if (source == null) {
                missing.add(id);
            } else {
                ObjectNode copy = source.deepCopy();
                copy.put("source", "local_snapshot");
                copy.put("source_version", runtime.reviewVersion);
                copy.put("provider_id", runtime.pack.packId);
                products.add(copy);
            }
        }
        return Map.of(
                "review_snapshot_version", runtime.reviewVersion,
                "data_source", source("local_snapshot", runtime.reviewVersion, runtime.pack.packId),
                "products", products,
                "missing_product_ids", missing);
    }

    public Map<String, Object> compatibility(String packId, JsonNode payload) {
        PackRuntime runtime = runtime(packId);
        ObjectNode object = objectPayload(payload);
        rejectUnknown(object, Set.of("domain_pack_id", "pairs"), "request");
        JsonNode pairsNode = object.get("pairs");
        if (pairsNode == null || !pairsNode.isArray() || pairsNode.size() > 50) {
            throw new JavaDataPlaneValidationException("pairs", "must be an array with at most 50 values");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < pairsNode.size(); i++) {
            JsonNode pairNode = pairsNode.get(i);
            if (!pairNode.isObject()) {
                throw new JavaDataPlaneValidationException("pairs[" + i + "]", "must be an object");
            }
            ObjectNode pair = (ObjectNode) pairNode;
            rejectUnknown(pair, PAIR_FIELDS, "pairs[" + i + "]");
            String productSku = requiredText(pair, "product_sku_id", "pairs[" + i + "]");
            String accessorySku = requiredText(pair, "accessory_sku_id", "pairs[" + i + "]");
            Item primary = runtime.itemsBySku.get(productSku);
            Item accessory = runtime.itemsBySku.get(accessorySku);
            Map<String, Object> evaluated = primary == null || accessory == null
                    ? evaluation("unknown", List.of("sku_not_found_in_graph"), null, List.of())
                    : evaluateCompatibility(runtime, primary, accessory);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("product_sku_id", productSku);
            result.put("accessory_sku_id", accessorySku);
            result.putAll(evaluated);
            result.put("graph_version", runtime.graphVersion);
            results.add(result);
        }
        return Map.of("graph_version", runtime.graphVersion, "results", results);
    }

    public Map<String, Object> quote(String packId, JsonNode payload) {
        PackRuntime runtime = runtime(packId);
        ObjectNode object = objectPayload(payload);
        rejectUnknown(object, Set.of("domain_pack_id", "offer_ids"), "request");
        List<String> offerIds = stringList(object, "offer_ids", false, 100);
        Instant issuedAt = clock.instant();
        Instant ttlEnd = issuedAt.plus(Duration.ofMinutes(5));
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (String offerId : offerIds) {
            Item item = runtime.itemsByOffer.get(offerId);
            if (item == null) {
                Map<String, Object> unavailable = new LinkedHashMap<>();
                unavailable.put("offer_id", offerId);
                unavailable.put("status", "unavailable");
                unavailable.put("amount", null);
                unavailable.put("currency", "CNY");
                unavailable.put("stock", 0);
                unavailable.put("valid_until", formatInstant(issuedAt));
                unavailable.put("reason", "offer_not_found");
                quotes.add(unavailable);
                continue;
            }
            Instant validUntil = item.offer.validUntil.isBefore(ttlEnd)
                    ? item.offer.validUntil
                    : ttlEnd;
            boolean active = item.offer.stock > 0 && validUntil.isAfter(issuedAt);
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("offer_id", offerId);
            quote.put("status", active ? "active" : "unavailable");
            quote.put("amount", active ? item.offer.price : null);
            quote.put("currency", item.offer.currency);
            quote.put("stock", active ? item.offer.stock : 0);
            quote.put("valid_until", formatInstant(validUntil));
            quote.put("reason", active ? "local_offer_snapshot" : "out_of_stock_or_expired");
            quotes.add(quote);
        }
        String issuedText = formatInstant(issuedAt);
        String digest = sha256Hex(String.join("|", offerIds) + issuedText).substring(0, 16);
        String version = "realtime-" + runtime.quoteVersion;
        return Map.of(
                "quote_batch_id", "quote-batch-" + digest,
                "quote_version", version,
                "issued_at", issuedText,
                "data_source", source("local_snapshot", version, runtime.pack.packId),
                "quotes", quotes);
    }

    public Map<String, Object> fuse(String packId, JsonNode payload) {
        PackRuntime runtime = runtime(packId);
        ObjectNode object = objectPayload(payload);
        rejectUnknown(object, FUSION_FIELDS, "request");
        JsonNode channelsNode = object.get("channels");
        if (channelsNode == null || !channelsNode.isArray() || channelsNode.size() > 3) {
            throw new JavaDataPlaneValidationException(
                    "channels", "must be an array with at most 3 values");
        }
        int limit = integer(object, "limit", 8, 1, 50);
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        Map<String, ObjectNode> organicCandidates = new LinkedHashMap<>();
        Map<String, ObjectNode> sponsoredCandidates = new LinkedHashMap<>();
        Map<String, CommerceFusionEngine.CandidateSignals> signals = new LinkedHashMap<>();

        for (int channelIndex = 0; channelIndex < channelsNode.size(); channelIndex++) {
            JsonNode channelNode = channelsNode.get(channelIndex);
            if (!channelNode.isObject()) {
                throw new JavaDataPlaneValidationException(
                        "channels[" + channelIndex + "]", "must be an object");
            }
            ObjectNode channelResult = (ObjectNode) channelNode;
            rejectUnknown(
                    channelResult,
                    FUSION_CHANNEL_FIELDS,
                    "channels[" + channelIndex + "]");
            for (String versionField : List.of("catalog_version", "quote_version")) {
                JsonNode version = channelResult.get(versionField);
                if (version != null && (!version.isTextual() || version.asText().isBlank())) {
                    throw new JavaDataPlaneValidationException(
                            "channels[" + channelIndex + "]." + versionField,
                            "must be a non-empty string");
                }
            }
            String channel = requiredText(
                    channelResult,
                    "channel",
                    "channels[" + channelIndex + "]");
            if (!"ads".equals(channel)
                    && !rrfWeightProfile.weights().containsKey(channel)) {
                throw new JavaDataPlaneValidationException("channel", "unsupported");
            }
            if (rankings.containsKey(channel)) {
                throw new JavaDataPlaneValidationException("channel", "duplicate");
            }

            JsonNode itemsNode = channelResult.get("items");
            if (itemsNode == null || !itemsNode.isArray() || itemsNode.size() > 100) {
                throw new JavaDataPlaneValidationException(
                        "channels[" + channelIndex + "].items",
                        "must be an array");
            }
            LinkedHashSet<String> ranking = new LinkedHashSet<>();
            for (int rank = 0; rank < itemsNode.size(); rank++) {
                ObjectNode candidate = candidateNode(
                        itemsNode.get(rank),
                        "channels[" + channelIndex + "].items[" + rank + "]",
                        runtime);
                if (!channel.equals(textOr(candidate, "channel", null))) {
                    throw new JavaDataPlaneValidationException(
                            "channels[" + channelIndex + "].items[" + rank + "].channel",
                            "must match its enclosing channel");
                }
                String sku = requiredText(candidate, "sku_id", "candidate");
                ranking.add(sku);

                String category = requiredText(candidate, "category", "candidate");
                double channelScore = number(candidate, "channel_score", 0.0);
                double adQuality = number(candidate, "ad_quality", 0.0);
                CommerceFusionEngine.CandidateSignals existing = signals.get(sku);
                if (existing != null && !category.equals(existing.category())) {
                    throw new JavaDataPlaneValidationException(
                            "candidate.category", "must be stable for a sku");
                }
                double organicScore = existing == null ? 0.0 : existing.organicScore();
                double sponsoredScore = existing == null ? 0.0 : existing.sponsoredScore();
                double strongestAdQuality = existing == null ? 0.0 : existing.adQuality();
                boolean sponsored = existing != null && existing.sponsored();
                if ("ads".equals(channel)) {
                    if (!candidate.path("sponsored").asBoolean()) {
                        throw new JavaDataPlaneValidationException(
                                "candidate.sponsored", "ads candidates must be disclosed");
                    }
                    sponsoredScore = Math.max(sponsoredScore, channelScore);
                    strongestAdQuality = Math.max(strongestAdQuality, adQuality);
                    sponsored = true;
                    sponsoredCandidates.putIfAbsent(sku, candidate);
                } else {
                    organicScore = Math.max(organicScore, channelScore);
                    organicCandidates.putIfAbsent(sku, candidate);
                }
                signals.put(sku, new CommerceFusionEngine.CandidateSignals(
                        category,
                        organicScore,
                        sponsoredScore,
                        strongestAdQuality,
                        sponsored));
            }
            rankings.put(channel, List.copyOf(ranking));
        }

        CommerceFusionEngine.FusionResult fused = CommerceFusionEngine.fuse(
                rrfWeightProfile,
                rankings,
                signals,
                limit);
        double minimum = fused.organicScores().values().stream()
                .min(Double::compare)
                .orElse(0.0);
        double maximum = fused.organicScores().values().stream()
                .max(Double::compare)
                .orElse(1.0);
        for (String sku : fused.organicRanking()) {
            ObjectNode item = organicCandidates.get(sku);
            if (item == null) continue;
            double rawScore = fused.organicScores().get(sku);
            item.put("normalized_score", round6(maximum == minimum
                    ? 1.0
                    : (rawScore - minimum) / (maximum - minimum)));
            item.put("ranking_score_type", "organic_weighted_rrf");
            item.remove("sources");
            ArrayNode sources = item.putArray("sources");
            fused.organicSources().getOrDefault(sku, List.of()).forEach(sources::add);
            addReason(item, String.format(
                    Locale.ROOT,
                    "organic_weighted_rrf=%.6f",
                    rawScore));
        }

        SponsoredPlacementPolicy sponsoredPolicy = rrfWeightProfile.sponsoredPolicy();
        for (String sku : fused.sponsoredIds()) {
            ObjectNode item = sponsoredCandidates.get(sku);
            if (item == null) continue;
            item.put("normalized_score", round6(clamp(
                    number(item, "channel_score", 0.0))));
            item.put("ranking_score_type", "sponsored_policy_gated");
            item.remove("sources");
            item.putArray("sources").add("ads");
            if (sponsoredPolicy.disclosureRequired()) {
                item.put("disclosure", "赞助");
            }
            addReason(item, "sponsored_policy_passed=" + sponsoredPolicy.policyId());
        }

        List<ObjectNode> slate = new ArrayList<>();
        Set<String> sponsoredIds = Set.copyOf(fused.sponsoredIds());
        Set<String> advertisedIds = Set.copyOf(rankings.getOrDefault("ads", List.of()));
        for (String sku : fused.slate()) {
            ObjectNode item = sponsoredIds.contains(sku)
                    ? sponsoredCandidates.get(sku)
                    : organicCandidates.get(sku);
            if (item == null) continue;
            if (!sponsoredIds.contains(sku) && advertisedIds.contains(sku)) {
                // The organic result remains eligible, but the same offer also entered
                // the Ads channel for this request, so the user-visible result needs
                // the same disclosure as an inserted sponsored placement.
                item.put("disclosure", "赞助");
                addReason(item, "sponsored_disclosure_overlap=organic_and_ads");
            }
            slate.add(item);
        }
        return Map.of(
                "fusion_version", "organic-weighted-rrf-v3",
                "organic_weight_profile", rrfWeightProfile.profileId(),
                "calibration_version", rrfWeightProfile.calibrationVersion(),
                "organic_weights", rrfWeightProfile.weights(),
                "sponsored_policy", Map.of(
                        "policy_id", sponsoredPolicy.policyId(),
                        "organic_relevance_floor_ratio",
                                sponsoredPolicy.organicRelevanceFloorRatio(),
                        "minimum_ad_quality", sponsoredPolicy.minimumAdQuality(),
                        "insertion_index", sponsoredPolicy.insertionIndex(),
                        "display_slot", sponsoredPolicy.insertionIndex() + 1,
                        "maximum_sponsored", sponsoredPolicy.maximumSponsored(),
                        "disclosure_required", sponsoredPolicy.disclosureRequired()),
                "items", slate,
                "organic_count", slate.stream()
                        .filter(item -> !item.path("sponsored").asBoolean())
                        .count(),
                "sponsored_count", slate.stream()
                        .filter(item -> item.path("sponsored").asBoolean())
                        .count());
    }

    public Map<String, Object> bundles(String packId, JsonNode payload) {
        PackRuntime runtime = runtime(packId);
        ObjectNode object = objectPayload(payload);
        rejectUnknown(object, BUNDLE_FIELDS, "request");
        JsonNode itemsNode = object.get("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.size() > 200) {
            throw new JavaDataPlaneValidationException("items", "must be an array with at most 200 values");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (int i = 0; i < itemsNode.size(); i++) {
            items.add(candidateNode(itemsNode.get(i), "items[" + i + "]", runtime));
        }
        List<String> requested = stringList(object, "requested_categories", false, 100);
        if (requested.isEmpty()) {
            throw new JavaDataPlaneValidationException(
                    "requested_categories", "must contain at least one category");
        }
        for (String category : requested) {
            if (!runtime.pack.categories.containsKey(category)) {
                throw new JavaDataPlaneValidationException("requested_categories", "category is outside selected domain pack");
            }
        }
        String intent = requiredText(object, "intent", "request");
        if (!Set.of("precise", "catalog", "exploratory", "bundle", "compare").contains(intent)) {
            throw new JavaDataPlaneValidationException("intent", "is unsupported");
        }
        int topN = integer(object, "top_n", 3, 1, 10);
        Double budget = optionalNumber(object, "budget_max");
        String primaryCategory = requested.contains(runtime.pack.primaryCategory)
                ? runtime.pack.primaryCategory
                : requested.get(0);
        List<String> requiredCategories = "bundle".equals(intent)
                ? requested.stream().filter(runtime.pack.defaultBundleCategories::contains).toList()
                : List.of(primaryCategory);

        Map<String, List<ObjectNode>> byCategory = new LinkedHashMap<>();
        for (String category : requiredCategories) {
            List<ObjectNode> categoryItems = items.stream()
                    .filter(item -> category.equals(textOr(item, "category", "")))
                    .toList();
            double naturalBest = categoryItems.stream()
                    .filter(item -> !item.path("sponsored").asBoolean())
                    .mapToDouble(item -> number(item, "normalized_score", 0.0))
                    .max().orElse(0.0);
            List<ObjectNode> protectedItems = categoryItems.stream()
                    .filter(item -> !item.path("sponsored").asBoolean()
                            || number(item, "normalized_score", 0.0) >= 0.85 * naturalBest)
                    .sorted(Comparator
                            .comparingDouble((ObjectNode item) -> -number(item, "normalized_score", 0.0))
                            .thenComparing(item -> textOr(item, "sku_id", "")))
                    .limit(8)
                    .toList();
            byCategory.put(category, new ArrayList<>(protectedItems));
        }

        List<Map<String, Object>> allBundles = new ArrayList<>();
        List<List<ObjectNode>> categoryLists = requiredCategories.stream()
                .map(category -> byCategory.getOrDefault(category, List.of()))
                .toList();
        if (categoryLists.stream().allMatch(list -> !list.isEmpty())) {
            enumerateBundles(runtime, categoryLists, 0, new ArrayList<>(), budget,
                    primaryCategory, allBundles);
        }
        allBundles.sort(Comparator
                .comparingDouble((Map<String, Object> item) -> -((Number) item.get("score")).doubleValue())
                .thenComparing(item -> ((Number) item.get("total_price")).doubleValue())
                .thenComparing(item -> String.join(",", castStrings(item.get("sku_ids")))));
        if (allBundles.isEmpty() && !byCategory.getOrDefault(primaryCategory, List.of()).isEmpty()) {
            ObjectNode item = byCategory.get(primaryCategory).get(0);
            allBundles.add(Map.of(
                    "sku_ids", List.of(textOr(item, "sku_id", "")),
                    "total_price", round2(number(item, "price", 0.0)),
                    "score", round6(number(item, "normalized_score", 0.0)),
                    "sponsored_count", item.path("sponsored").asBoolean() ? 1 : 0,
                    "compatibility", List.of()));
        }
        List<Map<String, Object>> selected = allBundles.stream().limit(topN).toList();
        boolean complete = !selected.isEmpty()
                && castStrings(selected.get(0).get("sku_ids")).size() == requiredCategories.size();
        return Map.of(
                "optimizer_version", "constraint-enumeration-v3-budget-target",
                "complete", complete,
                "bundles", selected);
    }

    public Map<String, Object> searchForQuery(RetailQuery query) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("query", query.originalQuery());
        payload.put("domain_pack_id", query.domainPackId());
        putStrings(payload, "requested_categories", query.requestedCategories());
        putStrings(payload, "use_cases", query.useCases());
        putStrings(payload, "preferred_brands", query.preferredBrands());
        if (query.budgetMax() == null) payload.putNull("max_price");
        else payload.put("max_price", query.budgetMax());
        payload.put("limit", 8);
        payload.put("sponsored_allowed", query.sponsoredAllowed());
        payload.put("personalization_enabled", true);
        return discovery(query.domainPackId(), "search", payload);
    }

    private Map<String, Object> search(PackRuntime runtime, DiscoveryRequest request) {
        String primaryCategory = request.requestedCategories.contains(runtime.pack.primaryCategory)
                ? runtime.pack.primaryCategory
                : request.requestedCategories.get(0);
        List<ObjectNode> candidates = new ArrayList<>();
        for (Item item : runtime.items) {
            if (!eligible(item, request) || !item.category.equals(primaryCategory)) continue;
            double termFit = termFit(runtime.pack, item, request);
            Personalization personalization = personalization(runtime, item, request);
            double brandFit = request.preferredBrands.contains(item.brand) ? 0.2 : 0.0;
            double value = request.maxPrice != null && request.maxPrice != 0
                    ? 0.15 * budgetFitness(request.maxPrice, item.offer.price, 0.85)
                    : 0.05;
            candidates.add(candidate(item, "search",
                    0.52 + 0.25 * termFit + brandFit + value + personalization.score,
                    joinReasons(List.of("structured_filter_passed",
                            String.format(Locale.ROOT, "query_term_fit=%.2f", termFit)),
                            personalization.reasons), runtime));
        }
        return response(runtime, "search", top(candidates, request.limit));
    }

    private Map<String, Object> recommend(PackRuntime runtime, DiscoveryRequest request) {
        Set<String> accessoryCategories = new LinkedHashSet<>(request.requestedCategories);
        accessoryCategories.remove(runtime.pack.primaryCategory);
        Set<String> allowed = accessoryCategories.isEmpty()
                ? Set.of(runtime.pack.primaryCategory)
                : accessoryCategories;
        Set<String> peerEcosystems = request.primaryProductIds.stream()
                .map(runtime.itemsByProductId::get)
                .filter(item -> item != null)
                .map(item -> item.ecosystem)
                .collect(Collectors.toSet());
        List<ObjectNode> candidates = new ArrayList<>();
        for (Item item : runtime.items) {
            if (!eligible(item, request) || !allowed.contains(item.category)
                    || !primaryAccessoryEligible(runtime, item, request)) continue;
            double termFit = termFit(runtime.pack, item, request);
            Personalization personalization = personalization(runtime, item, request);
            double universal = "universal".equals(item.ecosystem) ? 0.12 : 0.0;
            double peerFit = !peerEcosystems.isEmpty()
                    && ("universal".equals(item.ecosystem) || peerEcosystems.contains(item.ecosystem))
                    ? 0.08 : 0.0;
            double value = request.maxPrice != null && request.maxPrice != 0
                    ? 0.18 * (1 - item.offer.price / request.maxPrice)
                    : 0.05;
            List<String> reasons = new ArrayList<>();
            reasons.add("session_intent_match");
            reasons.add(universal > 0 ? "universal_ecosystem" : "ecosystem_specific");
            reasons.addAll(personalization.reasons);
            if (peerFit > 0) reasons.add("search_peer_context_match");
            candidates.add(candidate(item, "recommendation",
                    0.42 + 0.25 * termFit + universal + peerFit + value + personalization.score,
                    reasons, runtime));
        }
        return response(runtime, "recommendation",
                topByCategory(candidates, request.limit, allowed));
    }

    private Map<String, Object> ads(PackRuntime runtime, DiscoveryRequest request) {
        if (!request.sponsoredAllowed) return response(runtime, "ads", List.of());
        List<ObjectNode> candidates = new ArrayList<>();
        for (Item item : runtime.items) {
            if (!item.offer.sponsored || !eligible(item, request)) continue;
            double categoryFit = request.requestedCategories.contains(item.category) ? 1.0 : 0.0;
            double termFit = termFit(runtime.pack, item, request);
            double relevance = 0.65 * categoryFit + 0.35 * termFit;
            if (relevance < 0.45 || item.offer.adQuality < 0.5) continue;
            double bid = Math.min(1.0, item.offer.adBid / 3.0);
            double exposurePenalty = request.adExposureProductIds.contains(item.spuId) ? 0.2 : 0.0;
            List<String> reasons = new ArrayList<>();
            reasons.add(String.format(Locale.ROOT, "ad_relevance=%.2f", relevance));
            reasons.add("sponsored_disclosure_required");
            if (exposurePenalty > 0) reasons.add("ad_frequency_penalty");
            candidates.add(candidate(item, "ads",
                    0.7 * relevance + 0.2 * item.offer.adQuality + 0.1 * bid - exposurePenalty,
                    reasons, runtime));
        }
        return response(runtime, "ads", top(candidates, request.limit));
    }

    private DiscoveryRequest parseDiscovery(JsonNode payload, PackRuntime runtime) {
        ObjectNode object = objectPayload(payload);
        rejectUnknown(object, DISCOVERY_FIELDS, "request");
        List<String> categories = stringList(object, "requested_categories", true, 100);
        for (String category : categories) {
            if (!runtime.pack.categories.containsKey(category)) {
                throw new JavaDataPlaneValidationException("requested_categories", "unknown value: " + category);
            }
        }
        if (categories.isEmpty()) categories = List.of(runtime.pack.defaultCategory);
        return new DiscoveryRequest(
                textDefault(object, "query", "", 4096),
                categories,
                stringList(object, "use_cases", true, 100),
                stringList(object, "preferred_brands", true, 100),
                stringList(object, "primary_product_ids", true, 100),
                optionalNumber(object, "max_price"),
                integer(object, "limit", 8, 1, 100),
                booleanDefault(object, "sponsored_allowed", true),
                textDefault(object, "identity_id", "", 128),
                textDefault(object, "session_id", "", 128),
                booleanDefault(object, "personalization_enabled", true),
                stringList(object, "recent_product_ids", true, 100),
                stringList(object, "excluded_product_ids", true, 100),
                stringList(object, "ad_exposure_product_ids", true, 100));
    }

    private ObjectNode candidate(Item item, String channel, double score,
                                 Collection<String> reasons, PackRuntime runtime) {
        ObjectNode out = mapper.createObjectNode();
        out.put("spu_id", item.spuId);
        out.put("product_id", item.spuId);
        out.put("sku_id", item.skuId);
        out.put("offer_id", item.offer.offerId);
        out.put("title", item.title);
        out.put("category", item.category);
        out.put("brand", item.brand);
        out.put("price", item.offer.price);
        out.put("currency", item.offer.currency);
        out.put("stock", item.offer.stock);
        putStrings(out, "tags", item.tags);
        putStrings(out, "decision_facts", item.decisionFacts);
        putStrings(out, "tradeoffs", item.tradeoffs);
        out.put("ecosystem", item.ecosystem);
        putStrings(out, "connectors", item.connectors);
        putStrings(out, "protocols", item.protocols);
        if (item.maxPowerWatts == null) out.putNull("max_power_watts");
        else out.put("max_power_watts", item.maxPowerWatts);
        out.put("catalog_version", runtime.catalogVersion);
        out.put("quote_version", runtime.quoteVersion);
        out.put("quote_valid_until", formatInstant(item.offer.validUntil));
        out.put("ad_bid", item.offer.adBid);
        out.put("ad_quality", item.offer.adQuality);
        out.put("channel", channel);
        out.put("channel_score", round6(clamp(score)));
        out.put("normalized_score", 0.0);
        ArrayNode reasonArray = out.putArray("reasons");
        reasons.stream().filter(reason -> reason != null).forEach(reasonArray::add);
        out.put("sponsored", "ads".equals(channel));
        if ("ads".equals(channel)) out.put("disclosure", "赞助");
        else out.putNull("disclosure");
        return out;
    }

    private List<ObjectNode> top(List<ObjectNode> candidates, int limit) {
        candidates.sort(Comparator
                .comparingDouble((ObjectNode item) -> -number(item, "channel_score", 0.0))
                .thenComparing(item -> textOr(item, "sku_id", "")));
        List<ObjectNode> selected = new ArrayList<>(candidates.subList(0, Math.min(limit, candidates.size())));
        normalizeScores(selected);
        return selected;
    }

    private List<ObjectNode> topByCategory(
            List<ObjectNode> candidates,
            int limit,
            Collection<String> requestedCategories
    ) {
        candidates.sort(Comparator
                .comparingDouble((ObjectNode item) -> -number(item, "channel_score", 0.0))
                .thenComparing(item -> textOr(item, "sku_id", "")));
        Map<String, ObjectNode> selectedBySku = new LinkedHashMap<>();
        for (String category : requestedCategories) {
            if (selectedBySku.size() >= limit) break;
            candidates.stream()
                    .filter(item -> category.equals(textOr(item, "category", "")))
                    .findFirst()
                    .ifPresent(item -> selectedBySku.putIfAbsent(textOr(item, "sku_id", ""), item));
        }
        for (ObjectNode candidate : candidates) {
            if (selectedBySku.size() >= limit) break;
            selectedBySku.putIfAbsent(textOr(candidate, "sku_id", ""), candidate);
        }
        List<ObjectNode> selected = new ArrayList<>(selectedBySku.values());
        normalizeScores(selected);
        return selected;
    }

    private void normalizeScores(List<ObjectNode> selected) {
        double minimum = selected.stream().mapToDouble(item -> number(item, "channel_score", 0.0))
                .min().orElse(0.0);
        double maximum = selected.stream().mapToDouble(item -> number(item, "channel_score", 0.0))
                .max().orElse(0.0);
        for (ObjectNode item : selected) {
            item.put("normalized_score", round6(maximum == minimum
                    ? 1.0
                    : (number(item, "channel_score", 0.0) - minimum) / (maximum - minimum)));
        }
    }

    private double termFit(Pack pack, Item item, DiscoveryRequest request) {
        String haystack = String.join(" ", item.spuTitle, item.title, item.brand,
                String.join(" ", item.tags)).toLowerCase(Locale.ROOT);
        String query = request.query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        pack.commerceTerms().stream().filter(query::contains).forEach(terms::add);
        Matcher matcher = ASCII_TERM.matcher(query);
        while (matcher.find() && !"gb".equals(matcher.group())) terms.add(matcher.group());
        terms.addAll(request.useCases);
        terms.addAll(request.preferredBrands);
        if (terms.isEmpty()) return 0.5;
        long matches = terms.stream().filter(term -> haystack.contains(term.toLowerCase(Locale.ROOT))).count();
        return (double) matches / terms.size();
    }

    private Personalization personalization(PackRuntime runtime, Item item, DiscoveryRequest request) {
        if (!request.personalizationEnabled) return new Personalization(0.0, List.of("personalization_opted_out"));
        List<Item> recent = request.recentProductIds.stream()
                .map(runtime.itemsByProductId::get)
                .filter(value -> value != null)
                .toList();
        double shortTerm = 0.0;
        if (!recent.isEmpty()) {
            if (recent.stream().anyMatch(previous -> previous.brand.equals(item.brand))) shortTerm += 0.08;
            if (recent.stream().anyMatch(previous -> previous.category.equals(item.category))) shortTerm += 0.04;
        }
        double longTerm = 0.06 * stableAffinity(request.identityId, item.brand);
        List<String> reasons = new ArrayList<>(List.of(
                String.format(Locale.ROOT, "personalization=%.3f", shortTerm + longTerm)));
        if (shortTerm > 0) reasons.add("session_or_recent_affinity");
        return new Personalization(shortTerm + longTerm, reasons);
    }

    private boolean eligible(Item item, DiscoveryRequest request) {
        return item.offer.stock > 0
                && request.requestedCategories.contains(item.category)
                && (request.maxPrice == null || item.offer.price <= request.maxPrice)
                && !request.excludedProductIds.contains(item.spuId);
    }

    private boolean primaryAccessoryEligible(PackRuntime runtime, Item item, DiscoveryRequest request) {
        if (!request.requestedCategories.contains(runtime.pack.primaryCategory)) return true;
        Requirement requirement = runtime.pack.requirements.get(item.category);
        if (requirement == null) return true;
        boolean connectorOk = requirement.connectors.isEmpty()
                || item.connectors.stream().anyMatch(requirement.connectors::contains);
        boolean protocolOk = requirement.protocols.isEmpty()
                || item.protocols.stream().anyMatch(requirement.protocols::contains);
        return connectorOk && protocolOk;
    }

    private Map<String, Object> response(PackRuntime runtime, String channel, List<ObjectNode> items) {
        return Map.of(
                "channel", channel,
                "catalog_version", runtime.catalogVersion,
                "quote_version", runtime.quoteVersion,
                "data_source", source("local_snapshot", runtime.catalogVersion, runtime.pack.packId),
                "items", items);
    }

    private void enumerateBundles(PackRuntime runtime, List<List<ObjectNode>> categoryLists,
                                  int index, List<ObjectNode> combination, Double budget,
                                  String primaryCategory, List<Map<String, Object>> output) {
        if (index == categoryLists.size()) {
            LinkedHashSet<String> skuIds = combination.stream()
                    .map(item -> textOr(item, "sku_id", ""))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (skuIds.size() != combination.size()) return;
            double total = combination.stream().mapToDouble(item -> number(item, "price", 0.0)).sum();
            if (budget != null && total > budget) return;
            ObjectNode primaryCandidate = combination.stream()
                    .filter(item -> primaryCategory.equals(textOr(item, "category", "")))
                    .findFirst().orElse(null);
            Item primary = primaryCandidate == null ? null
                    : runtime.itemsBySku.get(textOr(primaryCandidate, "sku_id", ""));
            if (primary == null) return;
            List<Map<String, Object>> compatibility = new ArrayList<>();
            boolean compatible = true;
            for (ObjectNode accessoryCandidate : combination) {
                if (accessoryCandidate == primaryCandidate) continue;
                Item accessory = runtime.itemsBySku.get(textOr(accessoryCandidate, "sku_id", ""));
                Map<String, Object> evaluated = accessory == null
                        ? evaluation("unknown", List.of("sku_not_found_in_graph"), null, List.of())
                        : evaluateCompatibility(runtime, primary, accessory);
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("product_id", primary.spuId);
                edge.put("accessory_id", accessory == null
                        ? textOr(accessoryCandidate, "product_id", "") : accessory.spuId);
                edge.put("status", evaluated.get("status"));
                edge.put("reasons", evaluated.get("reasons"));
                edge.put("rule_version", runtime.graphVersion);
                edge.put("paths", evaluated.get("paths"));
                compatibility.add(edge);
                if (!"compatible".equals(evaluated.get("status"))) compatible = false;
            }
            if (!compatible) return;
            double relevance = combination.stream()
                    .mapToDouble(item -> number(item, "normalized_score", 0.0))
                    .average().orElse(0.0);
            int sponsored = (int) combination.stream().filter(item -> item.path("sponsored").asBoolean()).count();
            double budgetValue = budgetFitness(budget, total, 0.90);
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("sku_ids", new ArrayList<>(skuIds));
            bundle.put("total_price", round2(total));
            bundle.put("budget_utilization", budget == null || budget == 0
                    ? null : round6(total / budget));
            bundle.put("score", round6(0.68 * relevance + 0.32 * budgetValue - 0.04 * sponsored));
            bundle.put("sponsored_count", sponsored);
            bundle.put("compatibility", compatibility);
            output.add(bundle);
            return;
        }
        for (ObjectNode candidate : categoryLists.get(index)) {
            combination.add(candidate);
            enumerateBundles(runtime, categoryLists, index + 1, combination, budget, primaryCategory, output);
            combination.remove(combination.size() - 1);
        }
    }

    private Map<String, Object> evaluateCompatibility(PackRuntime runtime, Item primary, Item accessory) {
        Rule rule = runtime.rules.stream()
                .filter(value -> value.primaryCategory.equals(primary.category)
                        && value.accessoryCategory.equals(accessory.category))
                .findFirst().orElse(null);
        if (rule == null) {
            return evaluation("unknown", List.of("no_graph_rule_for_category_pair"), null, List.of());
        }
        List<String> connectors = intersection(primary.connectors, accessory.connectors, rule.requiredConnectors);
        List<String> protocols = intersection(primary.protocols, accessory.protocols, rule.requiredProtocols);
        boolean connectorOk = rule.requiredConnectors.isEmpty() || !connectors.isEmpty();
        boolean protocolOk = rule.requiredProtocols.isEmpty() || !protocols.isEmpty();
        List<String> paths = new ArrayList<>();
        connectors.forEach(value -> paths.add(primary.skuId + "-[HAS_CONNECTOR]->" + value
                + "<-[HAS_CONNECTOR]-" + accessory.skuId));
        protocols.forEach(value -> paths.add(primary.skuId + "-[SUPPORTS_PROTOCOL]->" + value
                + "<-[SUPPORTS_PROTOCOL]-" + accessory.skuId));
        String status = connectorOk && protocolOk ? "compatible" : connectorOk ? "unknown" : "incompatible";
        return evaluation(status, List.of(
                connectorOk ? "connector_graph_match" : "connector_graph_mismatch",
                protocolOk ? "protocol_graph_match" : "protocol_graph_unknown"), rule.ruleId, paths);
    }

    private Map<String, Object> evaluation(String status, List<String> reasons, String ruleId, List<String> paths) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("reasons", reasons);
        result.put("rule_id", ruleId);
        result.put("paths", paths);
        return result;
    }

    private PackRuntime runtime(String packId) {
        PackRuntime runtime = packs.get(packId);
        if (runtime == null) throw new JavaDataPlaneValidationException("domain_pack_id", "unknown domain pack");
        return runtime;
    }

    private String packId(JsonNode payload) {
        if (payload == null || payload.isNull()) return defaultPackId;
        if (!payload.isObject()) throw new JavaDataPlaneValidationException("request", "must be an object");
        JsonNode value = payload.get("domain_pack_id");
        if (value == null || value.isNull()) return defaultPackId;
        if (!value.isTextual() || value.asText().isBlank() || !value.asText().equals(value.asText().trim())) {
            throw new JavaDataPlaneValidationException("domain_pack_id", "must be a non-empty identifier");
        }
        return value.asText();
    }

    private PackRuntime loadPack(String domainResource) {
        try {
            JsonNode root = read(domainResource);
            String packId = requiredText(root, "pack_id", "domain");
            JsonNode assets = root.path("assets");
            String catalogName = requiredText(assets, "catalog", "assets");
            String reviewsName = requiredText(assets, "reviews", "assets");
            String compatibilityName = requiredText(assets, "compatibility", "assets");
            JsonNode catalog = read("data/" + catalogName);
            JsonNode reviews = read("data/" + reviewsName);
            JsonNode graph = read("data/" + compatibilityName);
            Pack pack = parsePack(root);
            List<Item> items = parseCatalog(catalog, pack);
            Map<String, ObjectNode> reviewMap = new LinkedHashMap<>();
            for (JsonNode product : reviews.path("products")) {
                if (product.isObject()) {
                    reviewMap.put(requiredText(product, "product_id", "review"), (ObjectNode) product.deepCopy());
                }
            }
            List<Rule> rules = new ArrayList<>();
            for (JsonNode rule : graph.path("rules")) {
                rules.add(new Rule(
                        requiredText(rule, "rule_id", "rule"),
                        requiredText(rule, "primary_category", "rule"),
                        requiredText(rule, "accessory_category", "rule"),
                        strings(rule.get("required_shared_connectors")),
                        strings(rule.get("required_shared_protocols"))));
            }
            Map<String, Item> bySku = new LinkedHashMap<>();
            Map<String, Item> byOffer = new LinkedHashMap<>();
            Map<String, Item> byProduct = new LinkedHashMap<>();
            for (Item item : items) {
                bySku.put(item.skuId, item);
                byOffer.put(item.offer.offerId, item);
                byProduct.putIfAbsent(item.spuId, item);
            }
            return new PackRuntime(
                    pack, items, bySku, byOffer, byProduct, reviewMap,
                    requiredText(reviews, "review_snapshot_version", "reviews"),
                    requiredText(graph, "graph_version", "graph"),
                    requiredText(catalog, "catalog_version", "catalog"),
                    requiredText(catalog, "quote_version", "catalog"), rules);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load Java data-plane asset", e);
        }
    }

    private Pack parsePack(JsonNode root) {
        Map<String, Category> categories = new LinkedHashMap<>();
        for (JsonNode value : root.path("categories")) {
            categories.put(requiredText(value, "id", "category"),
                    new Category(requiredText(value, "label", "category"), strings(value.get("terms"))));
        }
        Map<String, List<String>> brands = new LinkedHashMap<>();
        for (JsonNode value : root.path("brands")) {
            brands.put(requiredText(value, "name", "brand"), strings(value.get("terms")));
        }
        Map<String, Requirement> requirements = new LinkedHashMap<>();
        root.path("category_requirements").fields().forEachRemaining(entry ->
                requirements.put(entry.getKey(), new Requirement(
                        strings(entry.getValue().get("connectors_any")),
                        strings(entry.getValue().get("protocols_any")))));
        return new Pack(
                requiredText(root, "schema_version", "domain"),
                requiredText(root, "pack_id", "domain"),
                requiredText(root, "display_name", "domain"),
                requiredText(root, "description", "domain"),
                requiredText(root, "workflow_id", "domain"),
                requiredText(root, "capability_profile_id", "domain"),
                requiredText(root, "default_category", "domain"),
                requiredText(root, "primary_category", "domain"),
                strings(root.get("default_bundle_categories")),
                categories,
                strings(root.get("use_cases")),
                brands,
                strings(root.get("protocol_terms")),
                strings(root.get("example_queries")),
                requirements);
    }

    private List<Item> parseCatalog(JsonNode root, Pack pack) {
        List<Item> items = new ArrayList<>();
        for (JsonNode spu : root.path("spus")) {
            String spuId = requiredText(spu, "spu_id", "spu");
            String spuTitle = requiredText(spu, "title", "spu");
            String category = requiredText(spu, "category", "spu");
            String brand = requiredText(spu, "brand", "spu");
            List<String> tags = strings(spu.get("tags"));
            List<String> decisionFacts = strings(spu.get("decision_facts"));
            List<String> tradeoffs = strings(spu.get("tradeoffs"));
            for (JsonNode sku : spu.path("skus")) {
                String skuId = requiredText(sku, "sku_id", "sku");
                String title = requiredText(sku, "title", "sku");
                String ecosystem = requiredText(sku, "ecosystem", "sku");
                List<String> connectors = strings(sku.get("connectors"));
                List<String> protocols = strings(sku.get("protocols"));
                Integer maxPower = sku.hasNonNull("max_power_watts") ? sku.get("max_power_watts").asInt() : null;
                for (JsonNode offer : sku.path("offers")) {
                    items.add(new Item(
                            spuId, spuTitle, category, brand, tags, decisionFacts, tradeoffs,
                            skuId, title, ecosystem,
                            connectors, protocols, maxPower,
                            new Offer(
                                    requiredText(offer, "offer_id", "offer"),
                                    requiredText(offer, "seller_id", "offer"),
                                    offer.path("price").asDouble(),
                                    requiredText(offer, "currency", "offer"),
                                    offer.path("stock").asInt(),
                                    offer.path("sponsored").asBoolean(),
                                    Instant.parse(requiredText(offer, "valid_until", "offer")),
                                    offer.path("ad_bid").asDouble(0.0),
                                    offer.path("ad_quality").asDouble(0.0))));
                }
            }
        }
        return items;
    }

    private JsonNode read(String resource) throws IOException {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return mapper.readTree(input);
        }
    }

    private ObjectNode objectPayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new JavaDataPlaneValidationException("request", "must be an object");
        }
        return (ObjectNode) payload;
    }

    private void rejectUnknown(ObjectNode object, Set<String> allowed, String field) {
        List<String> unknown = new ArrayList<>();
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw new JavaDataPlaneValidationException(field, "unknown fields: " + String.join(", ", unknown));
        }
    }

    private List<String> stringList(ObjectNode object, String field, boolean allowMissing, int max) {
        JsonNode value = object.get(field);
        if (value == null && allowMissing) return List.of();
        if (value == null || !value.isArray() || value.size() > max) {
            throw new JavaDataPlaneValidationException(field, "must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || !item.asText().equals(item.asText().trim())) {
                throw new JavaDataPlaneValidationException(field, "values must be non-empty strings without surrounding whitespace");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private String requiredText(JsonNode object, String field, String context) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || !value.asText().equals(value.asText().trim())) {
            String errorField = "request".equals(context) ? field : context + "." + field;
            throw new JavaDataPlaneValidationException(
                    errorField, "must be a non-empty string without surrounding whitespace");
        }
        return value.asText();
    }

    private String textDefault(ObjectNode object, String field, String fallback, int max) {
        JsonNode value = object.get(field);
        if (value == null) return fallback;
        if (!value.isTextual() || value.asText().length() > max) {
            throw new JavaDataPlaneValidationException(field, "must be a string");
        }
        return value.asText();
    }

    private int integer(ObjectNode object, String field, int fallback, int min, int max) {
        JsonNode value = object.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || value.asInt() < min || value.asInt() > max) {
            throw new JavaDataPlaneValidationException(field, "must be an integer in range");
        }
        return value.asInt();
    }

    private Double optionalNumber(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) {
            throw new JavaDataPlaneValidationException(field, "must be a finite non-negative number or null");
        }
        return value.asDouble();
    }

    private boolean booleanDefault(ObjectNode object, String field, boolean fallback) {
        JsonNode value = object.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new JavaDataPlaneValidationException(field, "must be a boolean");
        return value.asBoolean();
    }

    private ObjectNode candidateNode(JsonNode value, String field, PackRuntime runtime) {
        if (value == null || !value.isObject()) {
            throw new JavaDataPlaneValidationException(field, "must be an object");
        }
        ObjectNode copy = (ObjectNode) value.deepCopy();
        for (String required : List.of(
                "spu_id", "product_id", "sku_id", "offer_id",
                "title", "category", "brand")) {
            JsonNode item = copy.get(required);
            if (item == null || !item.isTextual()) {
                throw new JavaDataPlaneValidationException(field + "." + required, "must be a string");
            }
        }
        if (!runtime.pack.categories.containsKey(copy.get("category").asText())) {
            throw new JavaDataPlaneValidationException(
                    field + ".category", "is outside the selected domain pack");
        }
        for (String required : List.of("price", "channel_score", "normalized_score")) {
            JsonNode item = copy.get(required);
            if (item == null || !item.isNumber() || !Double.isFinite(item.asDouble())) {
                throw new JavaDataPlaneValidationException(
                        field + "." + required, "must be a finite number");
            }
        }
        JsonNode stock = copy.get("stock");
        if (stock == null || !stock.isIntegralNumber()) {
            throw new JavaDataPlaneValidationException(field + ".stock", "must be an integer");
        }
        if (!"CNY".equals(textOr(copy, "currency", null))) {
            throw new JavaDataPlaneValidationException(field + ".currency", "must equal CNY");
        }
        if (!Set.of("search", "recommendation", "ads")
                .contains(textOr(copy, "channel", null))) {
            throw new JavaDataPlaneValidationException(field + ".channel", "is unsupported");
        }
        JsonNode reasons = copy.get("reasons");
        if (reasons == null || !reasons.isArray()) {
            throw new JavaDataPlaneValidationException(
                    field + ".reasons", "must be an array of strings");
        }
        for (JsonNode reason : reasons) {
            if (!reason.isTextual()) {
                throw new JavaDataPlaneValidationException(
                        field + ".reasons", "must be an array of strings");
            }
        }
        JsonNode sponsored = copy.get("sponsored");
        if (sponsored == null || !sponsored.isBoolean()) {
            throw new JavaDataPlaneValidationException(field + ".sponsored", "must be a boolean");
        }
        for (String optional : List.of("ad_quality", "ad_bid")) {
            JsonNode item = copy.get(optional);
            if (item != null && !item.isNull()
                    && (!item.isNumber() || !Double.isFinite(item.asDouble()))) {
                throw new JavaDataPlaneValidationException(
                        field + "." + optional, "must be a finite number");
            }
        }
        return copy;
    }

    private void putStrings(ObjectNode object, String field, Collection<String> values) {
        ArrayNode array = object.putArray(field);
        values.forEach(array::add);
    }

    private List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> { if (item.isTextual()) result.add(item.asText()); });
        return List.copyOf(result);
    }

    private String textOr(JsonNode object, String field, String fallback) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private double number(JsonNode object, String field, double fallback) {
        JsonNode value = object.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double budgetFitness(Double budget, double total, double targetRatio) {
        if (budget == null || budget <= 0) return 0.5;
        double ratio = total / budget;
        if (ratio > 1.0) return 0.0;
        return clamp(1.0 - Math.abs(targetRatio - ratio) / targetRatio);
    }

    private double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String formatInstant(Instant instant) {
        return UTC_FORMATTER.format(instant.atOffset(ZoneOffset.UTC));
    }

    private Map<String, Object> source(String kind, String version, String provider) {
        return Map.of("source", kind, "source_version", version, "provider_id", provider);
    }

    private double stableAffinity(String identityId, String value) {
        if (identityId == null || identityId.isBlank()) return 0.0;
        byte[] digest = sha256((identityId + "|" + value).getBytes(StandardCharsets.UTF_8));
        int unsigned = ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
        return unsigned / 65535.0;
    }

    private String sha256Hex(String value) {
        return java.util.HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private boolean containsText(ArrayNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private void addReason(ObjectNode item, String reason) {
        ArrayNode reasons = item.withArray("reasons");
        if (!containsText(reasons, reason)) reasons.add(reason);
    }

    private List<String> intersection(List<String> left, List<String> right, List<String> required) {
        Set<String> values = new TreeSet<>(left);
        values.retainAll(right);
        values.retainAll(required);
        return List.copyOf(values);
    }

    private List<String> castStrings(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
    }

    private List<String> joinReasons(Collection<String> first, Collection<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        first.stream().filter(value -> value != null).forEach(result::add);
        second.stream().filter(value -> value != null).forEach(result::add);
        return List.copyOf(result);
    }



    private record Personalization(double score, List<String> reasons) {
    }

    private record DiscoveryRequest(
            String query,
            List<String> requestedCategories,
            List<String> useCases,
            List<String> preferredBrands,
            List<String> primaryProductIds,
            Double maxPrice,
            int limit,
            boolean sponsoredAllowed,
            String identityId,
            String sessionId,
            boolean personalizationEnabled,
            List<String> recentProductIds,
            List<String> excludedProductIds,
            List<String> adExposureProductIds) {
    }

    private record Offer(
            String offerId,
            String sellerId,
            double price,
            String currency,
            int stock,
            boolean sponsored,
            Instant validUntil,
            double adBid,
            double adQuality) {
    }

    private record Item(
            String spuId,
            String spuTitle,
            String category,
            String brand,
            List<String> tags,
            List<String> decisionFacts,
            List<String> tradeoffs,
            String skuId,
            String title,
            String ecosystem,
            List<String> connectors,
            List<String> protocols,
            Integer maxPowerWatts,
            Offer offer) {
    }

    private record Category(String label, List<String> terms) {
    }

    private record Requirement(List<String> connectors, List<String> protocols) {
    }

    private record Rule(
            String ruleId,
            String primaryCategory,
            String accessoryCategory,
            List<String> requiredConnectors,
            List<String> requiredProtocols) {
    }

    private record Pack(
            String schemaVersion,
            String packId,
            String displayName,
            String description,
            String workflowId,
            String capabilityProfileId,
            String defaultCategory,
            String primaryCategory,
            List<String> defaultBundleCategories,
            Map<String, Category> categories,
            List<String> useCases,
            Map<String, List<String>> brands,
            List<String> protocolTerms,
            List<String> exampleQueries,
            Map<String, Requirement> requirements) {

        List<String> commerceTerms() {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            categories.values().forEach(category -> terms.addAll(category.terms));
            terms.addAll(useCases);
            brands.values().forEach(terms::addAll);
            terms.addAll(protocolTerms);
            return terms.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        }
    }

    private final class PackRuntime {
        private final Pack pack;
        private final List<Item> items;
        private final Map<String, Item> itemsBySku;
        private final Map<String, Item> itemsByOffer;
        private final Map<String, Item> itemsByProductId;
        private final Map<String, ObjectNode> reviews;
        private final String reviewVersion;
        private final String graphVersion;
        private final String catalogVersion;
        private final String quoteVersion;
        private final List<Rule> rules;

        private PackRuntime(
                Pack pack,
                List<Item> items,
                Map<String, Item> itemsBySku,
                Map<String, Item> itemsByOffer,
                Map<String, Item> itemsByProductId,
                Map<String, ObjectNode> reviews,
                String reviewVersion,
                String graphVersion,
                String catalogVersion,
                String quoteVersion,
                List<Rule> rules) {
            this.pack = pack;
            this.items = items;
            this.itemsBySku = itemsBySku;
            this.itemsByOffer = itemsByOffer;
            this.itemsByProductId = itemsByProductId;
            this.reviews = reviews;
            this.reviewVersion = reviewVersion;
            this.graphVersion = graphVersion;
            this.catalogVersion = catalogVersion;
            this.quoteVersion = quoteVersion;
            this.rules = rules;
        }

        private Map<String, Object> metadata() {
            List<Map<String, String>> categories = pack.categories.entrySet().stream()
                    .map(entry -> Map.of("id", entry.getKey(), "label", entry.getValue().label))
                    .toList();
            return Map.of(
                    "id", pack.packId,
                    "displayName", pack.displayName,
                    "description", pack.description,
                    "schemaVersion", pack.schemaVersion,
                    "workflowId", pack.workflowId,
                    "capabilityProfileId", pack.capabilityProfileId,
                    "categories", categories,
                    "exampleQueries", pack.exampleQueries);
        }
    }
}
