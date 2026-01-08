package com.buysense.retail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.sar.data.JavaRetailDataPlane;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public final class ReviewEvidenceGateway {
    private static final Pattern SAFE_VERSION =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final ObjectMapper mapper;
    private final DomainPackRegistry domains;
    private final ReviewEvidenceProvider localProvider;
    private final ReviewEvidenceProvider remoteProvider;
    private final boolean fallbackEnabled;

    @Autowired
    public ReviewEvidenceGateway(
            ObjectMapper mapper,
            DomainPackRegistry domains,
            JavaRetailDataPlane dataPlane,
            ReviewEvidenceProviderProperties properties
    ) {
        this(
                mapper,
                domains,
                new LocalSnapshotReviewEvidenceProvider(mapper, dataPlane),
                properties.enabled()
                        ? new HttpReviewEvidenceProvider(mapper, properties) : null,
                properties.fallbackEnabled());
    }

    ReviewEvidenceGateway(
            ObjectMapper mapper,
            DomainPackRegistry domains,
            ReviewEvidenceProvider localProvider,
            ReviewEvidenceProvider remoteProvider,
            boolean fallbackEnabled
    ) {
        this.mapper = mapper;
        this.domains = domains;
        this.localProvider = localProvider;
        this.remoteProvider = remoteProvider;
        this.fallbackEnabled = fallbackEnabled;
    }

    public ObjectNode reviews(String domainPackId, List<String> productIds) {
        domains.require(domainPackId);
        List<String> requested = validateProductIds(productIds);
        if (remoteProvider == null) {
            return local(domainPackId, requested, null);
        }
        try {
            return validateAndStamp(
                    remoteProvider.fetch(domainPackId, requested),
                    requested,
                    remoteProvider,
                    null);
        } catch (ProviderException error) {
            if (!fallbackEnabled) throw error;
            return local(domainPackId, requested, error.code());
        } catch (RuntimeException error) {
            ProviderException sanitized = new ProviderException(
                    "review_provider_invalid_response");
            if (!fallbackEnabled) throw sanitized;
            return local(domainPackId, requested, sanitized.code());
        }
    }

    private ObjectNode local(
            String domainPackId,
            List<String> requested,
            String fallbackReason
    ) {
        try {
            return validateAndStamp(
                    localProvider.fetch(domainPackId, requested),
                    requested,
                    localProvider,
                    fallbackReason);
        } catch (RuntimeException error) {
            throw new IllegalStateException("local review snapshot is invalid", error);
        }
    }

    private ObjectNode validateAndStamp(
            ObjectNode raw,
            List<String> requestedIds,
            ReviewEvidenceProvider provider,
            String fallbackReason
    ) {
        String sourceType = provider.sourceType();
        String providerId = provider.providerId();
        if (!Set.of("local_snapshot", "remote_provider").contains(sourceType)
                || providerId == null || providerId.isBlank() || providerId.length() > 64) {
            throw new IllegalArgumentException("review provider identity is invalid");
        }
        ObjectNode root = raw.deepCopy();
        String version = requiredText(root, "review_snapshot_version", 128);
        if (!SAFE_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("review snapshot version is invalid");
        }
        JsonNode rawProducts = root.path("products");
        if (!rawProducts.isArray() || rawProducts.size() > requestedIds.size()) {
            throw new IllegalArgumentException("review products are invalid");
        }
        Set<String> requested = new LinkedHashSet<>(requestedIds);
        Set<String> found = new LinkedHashSet<>();
        for (JsonNode rawProduct : rawProducts) {
            if (!(rawProduct instanceof ObjectNode product)) {
                throw new IllegalArgumentException("review product must be an object");
            }
            String productId = requiredText(product, "product_id", 128);
            if (!requested.contains(productId) || !found.add(productId)) {
                throw new IllegalArgumentException("review product id is unexpected");
            }
            int sampleSize = positiveInteger(product, "sample_size");
            JsonNode rawAspects = product.path("aspects");
            if (!rawAspects.isArray() || rawAspects.isEmpty() || rawAspects.size() > 100) {
                throw new IllegalArgumentException("review aspects are invalid");
            }
            product.put("source", sourceType);
            product.put("source_version", version);
            product.put("provider_id", providerId);
            for (JsonNode rawAspect : rawAspects) {
                if (!(rawAspect instanceof ObjectNode aspect)) {
                    throw new IllegalArgumentException("review aspect must be an object");
                }
                String aspectName = requiredText(aspect, "aspect", 120);
                JsonNode rawDimension = aspect.get("dimension");
                if (rawDimension == null || rawDimension.isNull()) {
                    aspect.put("dimension", aspectName);
                } else {
                    requiredText(aspect, "dimension", 120);
                }
                finiteBetween(aspect, "sentiment", -1, 1);
                int mentionCount = nonNegativeInteger(aspect, "mention_count");
                if (mentionCount > sampleSize) {
                    throw new IllegalArgumentException("mention_count exceeds sample_size");
                }
                finiteBetween(aspect, "confidence", 0, 1);
                requiredText(aspect, "summary", 1_000);
            }
        }
        List<String> missingValues = strings(root.path("missing_product_ids"), 100);
        Set<String> missing = new LinkedHashSet<>(missingValues);
        if (missing.size() != missingValues.size() || !requested.containsAll(missing)) {
            throw new IllegalArgumentException("missing review product ids are invalid");
        }
        Set<String> covered = new LinkedHashSet<>(found);
        if (found.stream().anyMatch(missing::contains)) {
            throw new IllegalArgumentException("review product partition overlaps");
        }
        covered.addAll(missing);
        if (!covered.equals(requested)) {
            throw new IllegalArgumentException(
                    "review response must cover every requested product id");
        }

        ObjectNode source = mapper.createObjectNode();
        source.put("source", sourceType);
        source.put("source_version", version);
        source.put("provider_id", providerId);
        if (fallbackReason != null) {
            source.put("fallback", true);
            source.put("fallback_reason", fallbackReason);
        }
        root.set("data_source", source);
        return root;
    }

    private List<String> validateProductIds(List<String> productIds) {
        if (productIds == null || productIds.size() > 100
                || productIds.stream().anyMatch(value -> value == null || value.isBlank()
                || !value.equals(value.trim()) || value.length() > 128
                || containsControlCharacters(value))) {
            throw new IllegalArgumentException("product ids are invalid");
        }
        List<String> copy = List.copyOf(productIds);
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("product ids are invalid");
        }
        return copy;
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()
                || !value.textValue().equals(value.textValue().trim())
                || value.textValue().length() > maxLength
                || containsControlCharacters(value.textValue())) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.textValue();
    }

    private int nonNegativeInteger(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.intValue();
    }

    private int positiveInteger(JsonNode node, String field) {
        int value = nonNegativeInteger(node, field);
        if (value == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private double finiteBetween(JsonNode node, String field, double min, double max) {
        JsonNode value = node.path(field);
        double number = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(number) || number < min || number > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return number;
    }

    private boolean containsControlCharacters(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private List<String> strings(JsonNode node, int maxValues) {
        if (!node.isArray() || node.size() > maxValues) {
            throw new IllegalArgumentException("missing_product_ids is invalid");
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(value -> {
                    if (!value.isTextual() || value.textValue().isBlank()
                            || !value.textValue().equals(value.textValue().trim())
                            || value.textValue().length() > 128
                            || containsControlCharacters(value.textValue())) {
                        throw new IllegalArgumentException("missing product id is invalid");
                    }
                    return value.textValue();
                })
                .toList();
    }

    public static final class ProviderException extends RuntimeException {
        private final String code;

        public ProviderException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
