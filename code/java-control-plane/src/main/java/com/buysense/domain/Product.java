package com.buysense.domain;

import java.math.BigDecimal;
import java.util.List;

public record Product(
        String id,
        String name,
        String category,
        String brand,
        BigDecimal price,
        List<String> tags,
        double qualityScore,
        double popularityScore,
        boolean sponsored,
        double bidScore,
        String compatibilityGroup,
        int stock,
        String source,
        String sourceVersion,
        String providerId,
        String productId,
        String skuId,
        String offerId,
        String currency,
        String catalogVersion,
        String quoteVersion,
        String quoteValidUntil,
        List<String> connectors,
        List<String> protocols,
        Integer maxPowerWatts
) {
    public Product {
        tags = List.copyOf(tags);
        connectors = connectors == null ? List.of() : List.copyOf(connectors);
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
        if (maxPowerWatts != null && maxPowerWatts < 0) {
            throw new IllegalArgumentException("maxPowerWatts must be non-negative");
        }
        if (source == null || source.isBlank()) {
            stock = stock <= 0 ? 99 : stock;
            source = "local_snapshot";
            sourceVersion = "legacy-catalog-v1";
            providerId = "normal-3c-v1";
        }
        productId = textOr(productId, skuPart(id));
        skuId = textOr(skuId, skuPart(id));
        offerId = textOr(offerId, offerPart(id));
        currency = textOr(currency, "CNY");
        catalogVersion = textOr(catalogVersion, sourceVersion);
        quoteVersion = textOr(quoteVersion, sourceVersion);
        quoteValidUntil = quoteValidUntil == null ? "" : quoteValidUntil;
    }

    public Product(
            String id,
            String name,
            String category,
            String brand,
            BigDecimal price,
            List<String> tags,
            double qualityScore,
            double popularityScore,
            boolean sponsored,
            double bidScore,
            String compatibilityGroup,
            int stock,
            String source,
            String sourceVersion,
            String providerId
    ) {
        this(id, name, category, brand, price, tags, qualityScore, popularityScore,
                sponsored, bidScore, compatibilityGroup, stock, source, sourceVersion, providerId,
                skuPart(id), skuPart(id), offerPart(id), "CNY", sourceVersion, sourceVersion, "",
                List.of(), List.of(), null);
    }

    public Product(
            String id,
            String name,
            String category,
            String brand,
            BigDecimal price,
            List<String> tags,
            double qualityScore,
            double popularityScore,
            boolean sponsored,
            double bidScore,
            String compatibilityGroup
    ) {
        this(id, name, category, brand, price, tags, qualityScore, popularityScore,
                sponsored, bidScore, compatibilityGroup, 99,
                "local_snapshot", "legacy-catalog-v1", "normal-3c-v1");
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String skuPart(String id) {
        if (id == null || id.isBlank()) return "unknown-sku";
        int separator = id.indexOf('@');
        return separator < 0 ? id : id.substring(0, separator);
    }

    private static String offerPart(String id) {
        if (id == null || id.isBlank()) return "unknown-offer";
        int separator = id.indexOf('@');
        return separator < 0 || separator == id.length() - 1 ? id : id.substring(separator + 1);
    }
}
