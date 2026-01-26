package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.catalog.CatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionRouterTest {
    private final IntentParser parser = new IntentParser(
            new DomainProperties(List.of("phone", "headphones", "charger")));
    private final ExecutionRouter router = new ExecutionRouter();

    @Test
    void keepsSimpleSingleCategoryRequestsOnTheWorkflowPath() {
        var route = router.route(parser.parse("预算5000元，推荐一台拍照手机"));

        assertThat(route.mode()).isEqualTo(ExecutionRouter.Mode.WORKFLOW);
        assertThat(route.clarificationRecommended()).isFalse();
        assertThat(route.reasons()).contains("single_category_deterministic_path");
    }

    @Test
    void routesCrossCategoryDecisionsToTheBoundedHybridPath() {
        var route = router.route(parser.parse(
                "预算7000元，配一套拍照手机、降噪耳机和充电器"));

        assertThat(route.mode()).isEqualTo(ExecutionRouter.Mode.HYBRID);
        assertThat(route.clarificationRecommended()).isFalse();
        assertThat(route.reasons()).contains("cross_category_bundle", "multiple_categories");
    }

    @Test
    void asksForClarificationInsteadOfInventingMissingHardConstraints() {
        var route = router.route(parser.parse("我不太懂，帮我选一个"));

        assertThat(route.mode()).isEqualTo(ExecutionRouter.Mode.HYBRID);
        assertThat(route.clarificationRecommended()).isTrue();
        assertThat(route.clarificationQuestion()).isNotBlank();
    }

    @Test
    void modelRewriteCanExpandRetrievalButCannotChangeUserHardConstraints() throws Exception {
        var original = parser.parse("预算5000元，不要广告，推荐拍照手机");
        var enriched = parser.enrich(
                original,
                "预算9000元，可以展示广告，推荐游戏手机和高端耳机");

        assertThat(enriched.budget()).isEqualByComparingTo("5000");
        assertThat(enriched.sponsoredAllowed()).isFalse();
        assertThat(enriched.requiredCategories()).containsExactly("phone");
        assertThat(enriched.retrievalQuery()).contains("游戏手机", "高端耳机");
        assertThat(enriched.useCases()).contains("photography", "gaming");

        var engine = new DecisionEngine(
                new CatalogRepository(new ObjectMapper().findAndRegisterModules()));
        assertThat(engine.decide(enriched).slate())
                .isNotEmpty()
                .noneMatch(candidate -> candidate.sponsored());
    }
}
