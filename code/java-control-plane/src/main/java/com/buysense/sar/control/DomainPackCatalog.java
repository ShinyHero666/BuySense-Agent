package com.buysense.sar.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads business vocabularies from the versioned domain-pack index. */
@Component
public class DomainPackCatalog {
    private final Map<String, DomainPackInfo> packs;
    private final String defaultPackId;

    public DomainPackCatalog() {
        this(new ObjectMapper());
    }

    @Autowired
    public DomainPackCatalog(ObjectMapper mapper) {
        DomainPackIndex index = DomainPackIndex.load(mapper);
        try {
            this.defaultPackId = index.defaultPackId();
            Map<String, DomainPackInfo> loaded = new LinkedHashMap<>();
            for (DomainPackIndex.Entry entry : index.packs()) {
                DomainPackInfo pack = parsePack(mapper, read(mapper, entry.resource()));
                if (!entry.packId().equals(pack.packId())) {
                    throw new IllegalStateException(
                            "domain pack index id does not match asset: " + entry.packId());
                }
                loaded.put(pack.packId(), pack);
            }
            this.packs = Map.copyOf(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load indexed domain pack assets", e);
        }
    }

    public String defaultPackId() {
        return defaultPackId;
    }

    public DomainPackInfo get(String packId) {
        DomainPackInfo pack = packs.get(packId);
        if (pack == null) throw new IllegalArgumentException("unknown domain pack: " + packId);
        return pack;
    }

    public List<DomainPackInfo> all() {
        return packs.values().stream()
                .sorted(Comparator.comparing(DomainPackInfo::packId))
                .toList();
    }

    /** Select the strongest vocabulary match, falling back to the configured default. */
    public DomainPackInfo resolve(String query) {
        String normalized = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        return packs.values().stream()
                .map(pack -> Map.entry(pack, matchScore(pack, normalized)))
                .max(Comparator.<Map.Entry<DomainPackInfo, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().packId().equals(defaultPackId) ? 1 : 0))
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElseGet(() -> packs.get(defaultPackId));
    }

    private static int matchScore(DomainPackInfo pack, String query) {
        int score = 0;
        for (String term : pack.allTerms()) {
            if (!term.isBlank() && query.contains(term)) score++;
        }
        return score;
    }

    private static DomainPackInfo parsePack(ObjectMapper mapper, JsonNode root) {
        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (JsonNode category : root.path("categories")) {
            categories.put(requiredText(category, "id", "category"), strings(category.get("terms")));
        }
        Map<String, List<String>> brands = new LinkedHashMap<>();
        for (JsonNode brand : root.path("brands")) {
            brands.put(requiredText(brand, "name", "brand"), strings(brand.get("terms")));
        }
        return new DomainPackInfo(
                requiredText(root, "pack_id", "domain"),
                requiredText(root, "display_name", "domain"),
                requiredText(root, "description", "domain"),
                requiredText(root, "workflow_id", "domain"),
                requiredText(root, "capability_profile_id", "domain"),
                requiredText(root, "default_category", "domain"),
                requiredText(root, "primary_category", "domain"),
                strings(root.get("default_bundle_categories")),
                categories,
                strings(root.get("use_cases")),
                brands,
                strings(root.get("protocol_terms")),
                strings(root.get("example_queries")));
    }

    private static JsonNode read(ObjectMapper mapper, String resource) throws IOException {
        ClassPathResource classPathResource = new ClassPathResource(resource);
        try (InputStream input = classPathResource.getInputStream()) {
            return mapper.readTree(input);
        }
    }

    private static String requiredText(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(context + "." + field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText());
        }
        return List.copyOf(values);
    }
}
