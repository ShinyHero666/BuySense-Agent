package com.buysense.retail;

import com.buysense.domain.Product;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RetailDataSnapshot(
        String domainPackId,
        String catalogVersion,
        String reviewVersion,
        String compatibilityVersion,
        List<Product> products,
        Map<String, CatalogItemMetadata> catalogMetadata,
        Map<String, ProductEvidence> evidenceByProduct,
        List<CompatibilityRule> compatibilityRules,
        Map<String, String> sources
) {
    public RetailDataSnapshot {
        products = List.copyOf(products);
        catalogMetadata = Map.copyOf(catalogMetadata);
        evidenceByProduct = Map.copyOf(evidenceByProduct);
        compatibilityRules = List.copyOf(compatibilityRules);
        sources = Map.copyOf(sources);
    }

    public boolean inStock(Product product) {
        CatalogItemMetadata metadata = catalogMetadata.get(product.id());
        return metadata == null || metadata.stock() > 0;
    }

    public double reviewFit(Product product, List<String> useCases) {
        CatalogItemMetadata metadata = catalogMetadata.get(product.id());
        ProductEvidence evidence = metadata == null ? null : evidenceByProduct.get(metadata.spuId());
        return evidence == null ? 0.5 : evidence.relevance(useCases);
    }

    public boolean compatible(List<Product> products, String primaryCategory) {
        if (products.size() <= 1) return true;
        Product primary = products.stream()
                .filter(product -> product.category().equals(primaryCategory))
                .findFirst()
                .orElse(products.get(0));
        CatalogItemMetadata primaryMetadata = catalogMetadata.get(primary.id());
        for (Product accessory : products) {
            if (accessory == primary) continue;
            CompatibilityRule rule = compatibilityRules.stream()
                    .filter(candidate -> candidate.primaryCategory().equals(primary.category()))
                    .filter(candidate -> candidate.accessoryCategory().equals(accessory.category()))
                    .findFirst()
                    .orElse(null);
            if (rule == null) {
                if (!legacyCompatible(primary, accessory)) return false;
                continue;
            }
            CatalogItemMetadata accessoryMetadata = catalogMetadata.get(accessory.id());
            if (primaryMetadata == null || accessoryMetadata == null) return false;
            boolean connectors = rule.requiredSharedConnectors().isEmpty()
                    || intersects(primaryMetadata.connectors(), accessoryMetadata.connectors(),
                    rule.requiredSharedConnectors());
            boolean protocols = rule.requiredSharedProtocols().isEmpty()
                    || intersects(primaryMetadata.protocols(), accessoryMetadata.protocols(),
                    rule.requiredSharedProtocols());
            if (!connectors || !protocols) return false;
        }
        return true;
    }

    private static boolean legacyCompatible(Product first, Product second) {
        Set<String> groups = new LinkedHashSet<>();
        for (String group : List.of(first.compatibilityGroup(), second.compatibilityGroup())) {
            if (group != null && !group.isBlank() && !group.equals("universal")) groups.add(group);
        }
        return groups.size() <= 1;
    }

    private static boolean intersects(List<String> left, List<String> right, List<String> allowed) {
        return left.stream().anyMatch(value -> right.contains(value) && allowed.contains(value));
    }

    public record CatalogItemMetadata(
            String spuId,
            String offerId,
            int stock,
            List<String> connectors,
            List<String> protocols,
            String source,
            String sourceVersion,
            String providerId
    ) {
        public CatalogItemMetadata {
            connectors = List.copyOf(connectors);
            protocols = List.copyOf(protocols);
        }
    }

    public record ProductEvidence(int sampleSize, List<ReviewAspect> aspects) {
        public ProductEvidence {
            aspects = List.copyOf(aspects);
        }

        public double relevance(List<String> useCases) {
            if (useCases.isEmpty() || aspects.isEmpty()) return 0.5;
            return aspects.stream()
                    .filter(aspect -> useCases.stream().anyMatch(term ->
                            aspect.aspect().equalsIgnoreCase(term)
                                    || aspect.summary().toLowerCase().contains(term.toLowerCase())))
                    .mapToDouble(aspect -> Math.max(0, aspect.sentiment()) * aspect.confidence())
                    .average()
                    .orElse(0.5);
        }
    }

    public record ReviewAspect(
            String aspect,
            double sentiment,
            int mentionCount,
            double confidence,
            String summary
    ) {
    }

    public record CompatibilityRule(
            String ruleId,
            String primaryCategory,
            String accessoryCategory,
            List<String> requiredSharedConnectors,
            List<String> requiredSharedProtocols
    ) {
        public CompatibilityRule {
            requiredSharedConnectors = List.copyOf(requiredSharedConnectors);
            requiredSharedProtocols = List.copyOf(requiredSharedProtocols);
        }
    }

    public record PriceQuote(
            String offerId,
            BigDecimal amount,
            int stock,
            String status
    ) {
    }
}
