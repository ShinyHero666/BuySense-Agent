package com.buysense.retail;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Provider contract for already-aggregated, product-level review evidence. */
public interface ReviewEvidenceProvider {
    String providerId();

    String sourceType();

    ObjectNode fetch(String domainPackId, List<String> productIds);
}
