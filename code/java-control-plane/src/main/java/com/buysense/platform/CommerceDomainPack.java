package com.buysense.platform;

import java.util.List;
import java.util.Map;

public record CommerceDomainPack(
        String schemaVersion,
        String packId,
        String displayName,
        String description,
        String workflowId,
        String capabilityProfileId,
        String defaultCategory,
        String primaryCategory,
        List<String> defaultBundleCategories,
        List<CategoryDefinition> categories,
        List<String> useCases,
        List<BrandDefinition> brands,
        List<String> protocolTerms,
        Map<String, CategoryRequirement> categoryRequirements,
        Assets assets,
        List<String> exampleQueries
) {
    public CommerceDomainPack {
        defaultBundleCategories = List.copyOf(defaultBundleCategories);
        categories = List.copyOf(categories);
        useCases = List.copyOf(useCases);
        brands = List.copyOf(brands);
        protocolTerms = List.copyOf(protocolTerms);
        categoryRequirements = Map.copyOf(categoryRequirements);
        exampleQueries = List.copyOf(exampleQueries);
    }

    public record CategoryDefinition(String id, String label, List<String> terms) {
        public CategoryDefinition {
            terms = List.copyOf(terms);
        }
    }

    public record BrandDefinition(String name, List<String> terms) {
        public BrandDefinition {
            terms = List.copyOf(terms);
        }
    }

    public record CategoryRequirement(List<String> connectorsAny, List<String> protocolsAny) {
        public CategoryRequirement {
            connectorsAny = List.copyOf(connectorsAny);
            protocolsAny = List.copyOf(protocolsAny);
        }
    }

    public record Assets(String catalog, String reviews, String compatibility, String queries) {
    }
}
