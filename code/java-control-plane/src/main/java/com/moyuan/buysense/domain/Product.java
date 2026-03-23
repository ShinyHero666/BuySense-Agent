package com.moyuan.buysense.domain;

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
        String providerId
) {
    public Product {
        tags = List.copyOf(tags);
        if (source == null || source.isBlank()) {
            stock = stock <= 0 ? 99 : stock;
            source = "local_snapshot";
            sourceVersion = "legacy-catalog-v1";
            providerId = "normal-3c-v1";
        }
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
}