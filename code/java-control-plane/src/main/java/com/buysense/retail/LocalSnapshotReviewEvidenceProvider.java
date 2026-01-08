package com.buysense.retail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.buysense.sar.data.JavaRetailDataPlane;

import java.util.List;

final class LocalSnapshotReviewEvidenceProvider implements ReviewEvidenceProvider {
    private final ObjectMapper mapper;
    private final JavaRetailDataPlane dataPlane;

    LocalSnapshotReviewEvidenceProvider(ObjectMapper mapper, JavaRetailDataPlane dataPlane) {
        this.mapper = mapper;
        this.dataPlane = dataPlane;
    }

    @Override
    public String providerId() {
        return "local-review-snapshot";
    }

    @Override
    public String sourceType() {
        return "local_snapshot";
    }

    @Override
    public ObjectNode fetch(String domainPackId, List<String> productIds) {
        ObjectNode request = mapper.createObjectNode();
        request.put("domain_pack_id", domainPackId);
        ArrayNode ids = request.putArray("product_ids");
        productIds.forEach(ids::add);
        JsonNode response = mapper.valueToTree(dataPlane.reviews(domainPackId, request));
        if (!response.isObject()) {
            throw new IllegalStateException("local review snapshot returned a non-object response");
        }
        return (ObjectNode) response;
    }
}
