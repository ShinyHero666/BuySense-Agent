package com.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.buysense.domain.Requirement.ConstraintSource;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.platform.ExtensionRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentParserTest {
    private final IntentParser parser = new IntentParser(
            new DomainPackRegistry(new ObjectMapper(), new ExtensionRegistry()));

    @Test
    void followsDomainPackRouterContract() {
        var requirement = parser.parse("预算7000元，给我配一套拍照设备");

        assertThat(requirement.budget()).isEqualByComparingTo("7000");
        assertThat(requirement.requiredCategories())
                .containsExactly("phone", "headphones", "charger");
        assertThat(requirement.useCases()).containsExactly("拍照");
        assertThat(requirement.constraints())
                .anyMatch(constraint -> constraint.source() == ConstraintSource.SYSTEM)
                .anyMatch(constraint -> constraint.source() == ConstraintSource.EXPLICIT_USER)
                .noneMatch(constraint -> constraint.source() == ConstraintSource.INFERRED_MODEL);
    }

    @Test
    void keepsAdOptOutAsTheAuthoritativePlanPolicy() {
        var requirement = parser.parse("预算5000元，不要广告，推荐拍照手机");

        assertThat(requirement.sponsoredAllowed()).isFalse();
        assertThat(requirement.requiredCategories()).containsExactly("phone");
        assertThat(requirement.constraints())
                .noneMatch(constraint -> constraint.field().equals("sponsored"));
    }
}
