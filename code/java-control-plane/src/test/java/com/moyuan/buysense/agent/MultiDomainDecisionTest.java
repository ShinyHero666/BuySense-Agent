package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Candidate;
import com.moyuan.buysense.domain.Requirement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MultiDomainDecisionTest {
    private static final String OUTDOOR = "outdoor-camping-v1";
    private final AgentTestFixture fixture = new AgentTestFixture();

    @Test
    void buildsAnOutdoorBundleFromTheSelectedDomainPackAndEnforcesHardConstraints() {
        Requirement requirement = fixture.parser.parse(
                "预算900元，帮我搭配一套适合高海拔露营的防风炉具、气罐和锅具，不要广告",
                OUTDOOR);
        var result = fixture.engine.decide(requirement, OUTDOOR);

        assertThat(requirement.requiredCategories())
                .containsExactlyInAnyOrder("camp_stove", "fuel_canister", "cookware");
        assertThat(requirement.useCases()).contains("high_altitude", "windproof");
        assertThat(result.slate()).isNotEmpty()
                .noneMatch(Candidate::sponsored)
                .allSatisfy(candidate -> {
                    assertThat(candidate.product().stock()).isPositive();
                    assertThat(candidate.product().source()).isEqualTo("local_snapshot");
                    assertThat(candidate.product().providerId()).isEqualTo(OUTDOOR);
                });
        assertThat(result.bundles()).isNotEmpty().allSatisfy(bundle -> {
            assertThat(bundle.items()).extracting(item -> item.category())
                    .containsExactlyInAnyOrder("camp_stove", "fuel_canister", "cookware");
            assertThat(bundle.totalPrice()).isLessThanOrEqualTo(new BigDecimal("900"));
            assertThat(bundle.budgetSatisfied()).isTrue();
            assertThat(bundle.compatible()).isTrue();
            assertThat(bundle.evidence()).hasSize(4);
        });
        assertThat(result.metrics())
                .containsEntry("domainPackId", OUTDOOR)
                .containsEntry("catalogSource", "local_snapshot")
                .containsEntry("pricingSource", "local_snapshot")
                .containsEntry("reviewSource", "local_snapshot");
    }

    @Test
    void doesNotFallThroughToAnotherCategoryWhenDeclaredInventoryIsUnavailable() {
        Requirement requirement = fixture.parser.parse("预算500元，推荐一条耐用数据线", "normal-3c-v1");
        var result = fixture.engine.decide(requirement, "normal-3c-v1");

        assertThat(requirement.requiredCategories()).containsExactly("cable");
        assertThat(result.slate()).isEmpty();
        assertThat(result.bundles()).isEmpty();
    }

    @Test
    void personalPreferenceChangesOnlyTheSoftRecommendationSignal() {
        Requirement base = fixture.parser.parse(
                "预算7000元，不要广告，推荐一款拍照手机",
                "normal-3c-v1");
        var withoutPreference = fixture.engine.decide(base, "normal-3c-v1");
        var withPreference = fixture.engine.decide(
                base.withPreferredBrand("Apple"), "normal-3c-v1");

        Candidate appleBefore = withoutPreference.slate().stream()
                .filter(candidate -> candidate.product().brand().equals("Apple"))
                .findFirst().orElseThrow();
        Candidate appleAfter = withPreference.slate().stream()
                .filter(candidate -> candidate.product().brand().equals("Apple"))
                .findFirst().orElseThrow();

        assertThat(appleAfter.channelScores().get("recommendation"))
                .isGreaterThan(appleBefore.channelScores().get("recommendation"));
        assertThat(withPreference.requirement().budget()).isEqualByComparingTo("7000");
        assertThat(withPreference.requirement().requiredCategories()).isEqualTo(Set.of("phone"));
        assertThat(withPreference.slate()).noneMatch(Candidate::sponsored);
    }
}