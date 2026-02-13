package com.moyuan.buysense.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class DomainPackRegistry {
    public static final String DEFAULT_PACK_ID = "normal-3c-v1";
    private static final Pattern VERSIONED_ID = Pattern.compile("^[a-z][a-z0-9-]*-v[0-9]+$");
    private static final Pattern CATEGORY_ID = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final List<String> MANIFESTS = List.of(
            "data/normal_3c_domain_v1.json",
            "data/outdoor_camping_domain_v1.json");

    private final Map<String, CommerceDomainPack> packs;

    public DomainPackRegistry(ObjectMapper mapper, ExtensionRegistry extensions) {
        LinkedHashMap<String, CommerceDomainPack> loaded = new LinkedHashMap<>();
        for (String manifest : MANIFESTS) {
            CommerceDomainPack pack = load(mapper, manifest);
            ExtensionRegistry.WorkflowDefinition workflow = extensions.requireWorkflow(pack.workflowId());
            if (!workflow.capabilityProfileId().equals(pack.capabilityProfileId())) {
                throw new IllegalStateException("domain capability profile mismatch: " + pack.packId());
            }
            if (loaded.putIfAbsent(pack.packId(), pack) != null) {
                throw new IllegalStateException("duplicate domain pack: " + pack.packId());
            }
        }
        if (!loaded.containsKey(DEFAULT_PACK_ID)) {
            throw new IllegalStateException("default domain pack is missing");
        }
        this.packs = Map.copyOf(loaded);
    }

    public CommerceDomainPack require(String packId) {
        CommerceDomainPack pack = packs.get(packId == null || packId.isBlank() ? DEFAULT_PACK_ID : packId);
        if (pack == null) throw new NoSuchElementException("unknown_domain_pack:" + packId);
        return pack;
    }

    public List<CommerceDomainPack> list() {
        return packs.values().stream().sorted(Comparator.comparing(CommerceDomainPack::packId)).toList();
    }

    public RegistryView view() {
        return new RegistryView(DEFAULT_PACK_ID, list().stream().map(pack -> new PackSummary(
                pack.packId(),
                pack.displayName(),
                pack.description(),
                pack.schemaVersion(),
                pack.workflowId(),
                pack.capabilityProfileId(),
                pack.categories().stream()
                        .map(category -> new CategorySummary(category.id(), category.label()))
                        .toList(),
                pack.exampleQueries())).toList());
    }

    private CommerceDomainPack load(ObjectMapper mapper, String manifestPath) {
        try (var input = new ClassPathResource(manifestPath).getInputStream()) {
            JsonNode root = mapper.readTree(input);
            String schemaVersion = text(root, "schema_version");
            if (!schemaVersion.equals("1.0")) throw new IllegalArgumentException("unsupported schema version");
            String packId = versionedId(root, "pack_id");
            String workflowId = versionedId(root, "workflow_id");
            String profileId = versionedId(root, "capability_profile_id");
            List<CommerceDomainPack.CategoryDefinition> categories = new ArrayList<>();
            Set<String> categoryIds = new LinkedHashSet<>();
            for (JsonNode category : array(root, "categories")) {
                String id = text(category, "id");
                if (!CATEGORY_ID.matcher(id).matches() || !categoryIds.add(id)) {
                    throw new IllegalArgumentException("invalid or duplicate category: " + id);
                }
                categories.add(new CommerceDomainPack.CategoryDefinition(
                        id, boundedText(category, "label", 120), strings(category, "terms")));
            }
            if (categories.isEmpty() || categories.size() > 100) {
                throw new IllegalArgumentException("domain categories must contain between 1 and 100 items");
            }
            String defaultCategory = category(root, "default_category", categoryIds);
            String primaryCategory = category(root, "primary_category", categoryIds);
            List<String> bundle = strings(root, "default_bundle_categories");
            bundle.forEach(value -> requireCategory(value, "default_bundle_categories", categoryIds));

            List<CommerceDomainPack.BrandDefinition> brands = new ArrayList<>();
            for (JsonNode brand : array(root, "brands")) {
                brands.add(new CommerceDomainPack.BrandDefinition(
                        boundedText(brand, "name", 120), strings(brand, "terms")));
            }
            Map<String, CommerceDomainPack.CategoryRequirement> requirements = new LinkedHashMap<>();
            JsonNode rawRequirements = root.path("category_requirements");
            rawRequirements.fields().forEachRemaining(entry -> {
                requireCategory(entry.getKey(), "category_requirements", categoryIds);
                requirements.put(entry.getKey(), new CommerceDomainPack.CategoryRequirement(
                        optionalStrings(entry.getValue(), "connectors_any"),
                        optionalStrings(entry.getValue(), "protocols_any")));
            });

            JsonNode assets = object(root, "assets");
            CommerceDomainPack.Assets packAssets = new CommerceDomainPack.Assets(
                    asset(assets, "catalog"),
                    asset(assets, "reviews"),
                    asset(assets, "compatibility"),
                    assets.hasNonNull("queries")
                            ? asset(assets, "queries")
                            : null);
            assertResource(packAssets.catalog());
            assertResource(packAssets.reviews());
            assertResource(packAssets.compatibility());
            if (packAssets.queries() != null) assertResource(packAssets.queries());

            List<String> examples = strings(root, "example_queries");
            if (examples.size() > 20) throw new IllegalArgumentException("too many example queries");
            return new CommerceDomainPack(
                    schemaVersion,
                    packId,
                    boundedText(root, "display_name", 120),
                    boundedText(root, "description", 500),
                    workflowId,
                    profileId,
                    defaultCategory,
                    primaryCategory,
                    bundle,
                    categories,
                    strings(root, "use_cases"),
                    brands,
                    strings(root, "protocol_terms"),
                    requirements,
                    packAssets,
                    examples);
        } catch (IOException error) {
            throw new IllegalStateException("failed to load domain pack: " + manifestPath, error);
        }
    }

    private static String versionedId(JsonNode root, String field) {
        String value = text(root, field);
        if (value.length() > 64 || !VERSIONED_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid versioned identifier: " + field);
        }
        return value;
    }

    private static String category(JsonNode root, String field, Set<String> categories) {
        String value = text(root, field);
        requireCategory(value, field, categories);
        return value;
    }

    private static void requireCategory(String value, String field, Set<String> categories) {
        if (!categories.contains(value)) throw new IllegalArgumentException(field + " references unknown category");
    }

    private static JsonNode object(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private static JsonNode array(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return value;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText().trim();
    }

    private static String boundedText(JsonNode root, String field, int maximum) {
        String value = text(root, field);
        if (value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return value;
    }

    private static List<String> strings(JsonNode root, String field) {
        List<String> values = optionalStrings(root, field);
        if (values.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return values;
    }

    private static List<String> optionalStrings(JsonNode root, String field) {
        JsonNode raw = root.path(field);
        if (!raw.isArray()) throw new IllegalArgumentException(field + " must be an array");
        List<String> values = new ArrayList<>();
        raw.forEach(item -> {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(field + " contains an invalid value");
            }
            values.add(item.asText().trim());
        });
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " contains duplicate values");
        }
        return List.copyOf(values);
    }

    private static String asset(JsonNode root, String field) {
        String path = text(root, field);
        if (path.contains("/") || path.contains("\\") || !path.endsWith(".json")) {
            throw new IllegalArgumentException("asset must be a local JSON filename: " + path);
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("invalid asset path: " + path);
        }
        return path;
    }

    private static void assertResource(String path) {
        if (!new ClassPathResource("data/" + path).exists()) {
            throw new IllegalArgumentException("domain asset does not exist: " + path);
        }
    }

    public record RegistryView(String defaultPackId, List<PackSummary> packs) {
    }

    public record PackSummary(
            String id,
            String displayName,
            String description,
            String schemaVersion,
            String workflowId,
            String capabilityProfileId,
            List<CategorySummary> categories,
            List<String> exampleQueries
    ) {
    }

    public record CategorySummary(String id, String label) {
    }
}
