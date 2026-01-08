package com.buysense.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelPortPropertiesTest {
    @Test
    void disablesOnlyNamedRolesAndCopiesExperimentConfiguration() {
        ModelPortProperties properties = new ModelPortProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:9999");
        properties.setApiKey("key");
        properties.setModel("target-model");
        properties.setThinkingMode("enabled");
        properties.setRoleModels(Map.of(" Search ", "search-model"));
        properties.setDisabledRoles(Set.of(" Critic ", "LEAD"));

        assertThat(properties.isRoleEnabled("critic")).isFalse();
        assertThat(properties.isRoleEnabled("lead")).isFalse();
        assertThat(properties.isRoleEnabled("search")).isTrue();
        assertThat(properties.modelForRole("SEARCH")).isEqualTo("search-model");
        assertThat(properties.modelForRole("ads")).isEqualTo("target-model");

        ModelPortProperties copy = properties.copy();
        assertThat(copy).isNotSameAs(properties);
        assertThat(copy.getDisabledRoles()).containsExactlyInAnyOrder("critic", "lead");
        assertThat(copy.getModel()).isEqualTo("target-model");
        assertThat(copy.getThinkingMode()).isEqualTo("enabled");
        copy.setDisabledRoles(Set.of("critic"));
        assertThat(copy.modelForRole("search")).isEqualTo("search-model");
        assertThat(properties.getDisabledRoles()).containsExactlyInAnyOrder("critic", "lead");
        assertThat(copy.isRoleEnabled("lead")).isTrue();
    }

    @Test
    void disablingTransportDisablesEveryRole() {
        ModelPortProperties properties = new ModelPortProperties();
        properties.setEnabled(false);

        assertThat(properties.isRoleEnabled("search")).isFalse();
        assertThat(properties.isRoleEnabled("critic")).isFalse();
    }
}
