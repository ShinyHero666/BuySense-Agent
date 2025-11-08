package com.moyuan.buysense.retail;

import com.fasterxml.jackson.databind.JsonNode;
import com.moyuan.buysense.platform.CommerceDomainPack;

import java.util.List;

interface RetailProvider {
    String providerId();

    default String mode() {
        return "http";
    }

    JsonNode catalog(CommerceDomainPack pack);

    JsonNode reviews(CommerceDomainPack pack, List<String> productIds);

    JsonNode pricing(CommerceDomainPack pack, List<String> offerIds);

    default void beforeConfirmation(String domainPackId) {
        // Providers without a catalog cache need no preparation.
    }

    default boolean allowsFallback(String errorCode) {
        return true;
    }
}
