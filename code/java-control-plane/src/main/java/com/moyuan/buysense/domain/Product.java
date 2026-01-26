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
        String compatibilityGroup
) {
}
