package com.buysense.sar.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared, validated registry for all versioned domain-pack assets. */
public record DomainPackIndex(
        String defaultPackId,
        List<Entry> packs) {
    private static final String INDEX_RESOURCE = "data/domain_pack_index.json";

    public DomainPackIndex {
        defaultPackId = Objects.requireNonNull(defaultPackId);
        packs = List.copyOf(packs);
        if (defaultPackId.isBlank() || packs.isEmpty()) {
            throw new IllegalStateException("domain pack index must define packs and a default");
        }
        Set<String> ids = new HashSet<>();
        for (Entry entry : packs) {
            if (!ids.add(entry.packId())) {
                throw new IllegalStateException("duplicate domain pack id: " + entry.packId());
            }
        }
        if (!ids.contains(defaultPackId)) {
            throw new IllegalStateException("default domain pack is missing: " + defaultPackId);
        }
    }

    public static DomainPackIndex load(ObjectMapper mapper) {
        try {
            JsonNode root;
            try (InputStream input = new ClassPathResource(INDEX_RESOURCE).getInputStream()) {
                root = mapper.readTree(input);
            }
            String defaultPackId = requiredText(root, "default_pack_id");
            if (!root.path("packs").isArray()) {
                throw new IllegalStateException("domain pack index packs must be an array");
            }
            List<Entry> entries = java.util.stream.StreamSupport
                    .stream(root.path("packs").spliterator(), false)
                    .map(value -> new Entry(
                            requiredText(value, "pack_id"),
                            requiredText(value, "resource")))
                    .toList();
            return new DomainPackIndex(defaultPackId, entries);
        } catch (IOException error) {
            throw new IllegalStateException("failed to load domain pack index", error);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(INDEX_RESOURCE + "." + field
                    + " must be a non-empty string");
        }
        return value.asText();
    }

    public record Entry(String packId, String resource) {
        public Entry {
            packId = Objects.requireNonNull(packId);
            resource = Objects.requireNonNull(resource);
            if (packId.isBlank() || resource.isBlank()) {
                throw new IllegalStateException("domain pack index entry must not be blank");
            }
        }
    }
}
