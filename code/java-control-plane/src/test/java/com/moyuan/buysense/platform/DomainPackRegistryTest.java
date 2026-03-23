package com.moyuan.buysense.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainPackRegistryTest {
    private final ExtensionRegistry extensions = new ExtensionRegistry();
    private final DomainPackRegistry domains = new DomainPackRegistry(
            new ObjectMapper().findAndRegisterModules(), extensions);

    @Test
    void loadsVersionedCommerceDomainsWithoutLosingTheOriginalThreeCDomain() {
        assertThat(domains.list()).extracting(CommerceDomainPack::packId)
                .containsExactly("normal-3c-v1", "outdoor-camping-v1");
        assertThat(domains.require(null).packId()).isEqualTo(DomainPackRegistry.DEFAULT_PACK_ID);

        assertThat(domains.require("normal-3c-v1").categories())
                .extracting(CommerceDomainPack.CategoryDefinition::id)
                .containsExactly("phone", "headphones", "charger", "cable", "case",
                        "laptop", "mouse", "keyboard");
        assertThat(domains.require("outdoor-camping-v1").defaultBundleCategories())
                .containsExactly("camp_stove", "fuel_canister", "cookware");
        assertThat(domains.view().packs()).hasSize(2)
                .allSatisfy(pack -> {
                    assertThat(pack.workflowId()).isEqualTo(ExtensionRegistry.DEFAULT_WORKFLOW_ID);
                    assertThat(pack.capabilityProfileId()).isEqualTo(ExtensionRegistry.DEFAULT_PROFILE_ID);
                });
    }

    @Test
    void exposesTenCapabilitiesAndRejectsDelegationOutsideTheBoundedGraph() {
        assertThat(extensions.capabilities()).hasSize(10)
                .extracting(ExtensionRegistry.CapabilityDefinition::role)
                .contains("lead", "intent_router", "search", "recommendation", "ads",
                        "compatibility", "pricing", "review_evidence", "critic");

        extensions.requireDelegation(ExtensionRegistry.DEFAULT_WORKFLOW_ID, "lead", "search");
        extensions.requireDelegation(ExtensionRegistry.DEFAULT_WORKFLOW_ID, "critic", "recommendation");
        assertThatThrownBy(() -> extensions.requireDelegation(
                ExtensionRegistry.DEFAULT_WORKFLOW_ID, "ads", "pricing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ads -> pricing");
    }
}