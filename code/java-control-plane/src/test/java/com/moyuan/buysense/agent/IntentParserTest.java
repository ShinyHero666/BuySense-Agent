package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Requirement.ConstraintSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentParserTest {
    private final IntentParser parser = new AgentTestFixture().parser;

    @Test
    void expandsConfiguredBundleAndKeepsConstraintProvenance() {
        var requirement = parser.parse("预算7000元，给我配一套拍照设备");

        assertThat(requirement.budget()).isEqualByComparingTo("7000");
        assertThat(requirement.requiredCategories()).containsExactlyInAnyOrder("phone", "headphones", "charger");
        assertThat(requirement.useCases()).containsExactly("photography");
        assertThat(requirement.constraints())
                .anyMatch(constraint -> constraint.source() == ConstraintSource.SYSTEM)
                .anyMatch(constraint -> constraint.source() == ConstraintSource.MODEL)
                .anyMatch(constraint -> constraint.source() == ConstraintSource.USER);
    }

    @Test
    void doesNotForcePhoneBundleWhenUserExplicitlyRequestsComputerSet() {
        var requirement = parser.parse("预算9000元，配一套游戏电脑、鼠标和键盘");

        assertThat(requirement.requiredCategories()).containsExactlyInAnyOrder("laptop", "mouse", "keyboard");
        assertThat(requirement.requiredCategories()).doesNotContain("phone");
    }

    @Test
    void recordsNoAdsAsAUserHardConstraint() {
        var requirement = parser.parse("预算5000元，不要广告，推荐拍照手机");

        assertThat(requirement.sponsoredAllowed()).isFalse();
        assertThat(requirement.constraints()).anySatisfy(constraint -> {
            assertThat(constraint.field()).isEqualTo("sponsored");
            assertThat(constraint.source()).isEqualTo(ConstraintSource.USER);
            assertThat(constraint.strength().name()).isEqualTo("HARD");
            assertThat(constraint.value()).isEqualTo(false);
        });
    }
}
