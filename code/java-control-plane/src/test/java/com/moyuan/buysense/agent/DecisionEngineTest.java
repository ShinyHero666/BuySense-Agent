package com.moyuan.buysense.agent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEngineTest {
    private final AgentTestFixture fixture = new AgentTestFixture();
    private final IntentParser parser = fixture.parser;
    private final DecisionEngine engine = fixture.engine;

    @Test
    void producesBudgetSafeCompatibleBundlesAndProtectsOrganicTopThree() {
        var result = engine.decide(parser.parse("预算7000元，配一套拍照手机、降噪耳机和充电器"));

        assertThat(result.bundles()).isNotEmpty().allSatisfy(bundle -> {
            assertThat(bundle.budgetSatisfied()).isTrue();
            assertThat(bundle.compatible()).isTrue();
            assertThat(bundle.totalPrice()).isLessThanOrEqualTo(new BigDecimal("7000"));
        });
        assertThat(result.slate().stream().limit(3).filter(candidate -> candidate.sponsored()).count())
                .isLessThanOrEqualTo(1);
    }

    @Test
    void excludesSponsoredProductsWhenUserRejectsAds() {
        var result = engine.decide(parser.parse("预算5000元，不要广告，推荐一台适合游戏和拍照的手机"));

        assertThat(result.requirement().sponsoredAllowed()).isFalse();
        assertThat(result.slate()).isNotEmpty().noneMatch(candidate -> candidate.sponsored());
        assertThat(result.metrics()).containsEntry("adCandidates", 0);
    }

    @Test
    void excludesSingleProductsAboveTheUserBudget() {
        var result = engine.decide(parser.parse(
                "预算5000元，不要广告，推荐拍照手机"));

        assertThat(result.slate()).isNotEmpty().allSatisfy(candidate ->
                assertThat(candidate.product().price())
                        .isLessThanOrEqualTo(new BigDecimal("5000")));
        assertThat(result.slate().get(0).product().category()).isEqualTo("phone");
        assertThat(result.slate().get(0).sponsored()).isFalse();
    }

    @Test
    void keepsAtMostOneSponsoredResultInTopThreeWhenOrganicSupplyIsSmall() {
        var result = engine.decide(parser.parse(
                "预算5000元，推荐拍照手机"));

        assertThat(result.slate()).isNotEmpty().allSatisfy(candidate ->
                assertThat(candidate.product().price())
                        .isLessThanOrEqualTo(new BigDecimal("5000")));
        assertThat(result.slate().stream().limit(3)
                .filter(candidate -> candidate.sponsored()).count())
                .isLessThanOrEqualTo(1);
    }

    @Test
    void validatesOneHundredTwentyCategoryAndAdPolicyScenarios() {
        Map<String, List<String>> categoryPhrases = Map.of(
                "phone", List.of("手机", "拍照手机", "安卓手机", "iPhone", "phone"),
                "headphones", List.of("耳机", "降噪耳机", "蓝牙耳机", "headphone", "earbuds"),
                "charger", List.of("充电器", "充电头", "快充", "快充充电器", "charger"),
                "laptop", List.of("电脑", "游戏电脑", "办公电脑", "笔记本", "laptop"),
                "mouse", List.of("鼠标", "游戏鼠标", "办公鼠标", "无线鼠标", "mouse"),
                "keyboard", List.of("键盘", "游戏键盘", "办公键盘", "机械键盘", "keyboard")
        );
        int scenarioCount = 0;

        for (var category : categoryPhrases.entrySet()) {
            for (String phrase : category.getValue()) {
                for (String useCase : List.of("游戏", "办公")) {
                    for (boolean rejectAds : List.of(false, true)) {
                        String query = "预算10000元，推荐一款" + phrase + "，主要用于" + useCase
                                + (rejectAds ? "，不要广告" : "");
                        var requirement = parser.parse(query);
                        var result = engine.decide(requirement);

                        assertThat(requirement.requiredCategories()).as(query)
                                .containsExactly(category.getKey());

                        assertThat(result.slate()).as(query).isNotEmpty()
                                .allMatch(candidate -> candidate.product().category().equals(category.getKey()));
                        assertThat(result.slate().stream().limit(3)
                                .filter(candidate -> candidate.sponsored()).count()).as(query)
                                .isLessThanOrEqualTo(1);
                        if (rejectAds) {
                            assertThat(result.slate()).as(query)
                                    .noneMatch(candidate -> candidate.sponsored());
                        }
                        scenarioCount++;
                    }
                }
            }
        }

        assertThat(scenarioCount).isEqualTo(120);
    }
}
