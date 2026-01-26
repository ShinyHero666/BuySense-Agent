package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Product;

import java.util.List;

public interface CommerceDomainPolicy {
    String domainId();

    boolean compatible(List<Product> products);

    default List<String> evidenceSources() {
        return List.of("catalog_snapshot", "price_snapshot", domainId() + "_compatibility_policy");
    }
}
