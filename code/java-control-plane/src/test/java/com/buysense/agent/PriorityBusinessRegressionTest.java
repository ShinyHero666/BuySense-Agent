package com.buysense.agent;

import com.buysense.domain.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PriorityBusinessRegressionTest {
    @Autowired private SearchAdsRecsLeadService service;
    @Autowired private ObjectMapper mapper;

    @Test
    void mixedScoreRatherThanModelOrderControlsRankingAndCannotInventProducts() throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree("""
                {"items":[{"sku_id":"suitable","normalized_score":0.95},
                          {"sku_id":"poor","normalized_score":0.1}]}
                """);
        var result = service.resolveModelRanking(source, mapper.readTree("""
                {"rankedSkuIds":["invented","poor","suitable"]}
                """));
        assertThat(result.response().path("items").get(0).path("sku_id").asText()).isEqualTo("suitable");
        assertThat(result.corrections()).contains("unknown_sku_removed");
        assertThat(source.path("items").get(0).path("normalized_score").asDouble()).isEqualTo(0.95);
        assertThat(result.response().path("items").get(0).path("normalized_score").asDouble()).isEqualTo(0.86);
    }

    @Test
    void neverPurchasesAnExplicitlyRejectedBrand() {
        var result = decide("预算7000元，不要苹果，推荐拍照手机");
        assertThat(result.result().slate()).isNotEmpty().allMatch(c -> !c.product().brand().equals("Apple"));
        assertThat(result.result().requirement().excludedBrands()).contains("Apple");
    }

    @Test
    void lowUtilizationDoesNotVetoAnOtherwiseCompleteBundle() {
        var result = decide("预算100000元，搭配手机、耳机和充电器，不要广告");
        assertThat(result.result().runtime().criticVerdict()).isEqualTo("approved");
        assertThat(result.result().bundles().get(0).totalPrice()).isLessThan(new java.math.BigDecimal("65000"));
        assertThat(result.result().bundles().get(0).items()).extracting(Product::category)
                .contains("phone", "headphones", "charger");
    }

    @Test
    void singleProductDoesNotForceAccessoryRecommendation() {
        var result = decide("预算5000元，不要广告，推荐手机");
        assertThat(result.tasks()).noneMatch(task -> task.role().equals("recommendation"));
        assertThat(result.result().runtime().criticVerdict()).isEqualTo("approved");
    }

    @Test
    void missingCategoryReturnsAQuestionAndNoPurchasePlan() {
        var result = decide("预算5000元，适合我就行");
        assertThat(result.result().metrics()).containsEntry("clarificationRequired", true);
        assertThat(result.result().runtime().explanation()).contains("商品类别");
        assertThat(result.result().slate()).isEmpty();
        assertThat(result.tasks()).noneMatch(task -> task.role().equals("search"));
    }

    @Test
    void noBudgetStillAllowsSingleProductDiscovery() {
        var result = decide("推荐拍照手机，不要广告");
        assertThat(result.result().requirement().budget()).isNull();
        assertThat(result.result().slate()).isNotEmpty();
    }

    @Test
    void explicitNonDefaultAccessoryIsNotSilentlyDropped() {
        var result = decide("预算7000元，搭配手机和数据线");
        assertThat(result.result().requirement().requiredCategories()).containsExactly("phone", "cable");
        assertThat(result.result().runtime().criticVerdict()).isEqualTo("vetoed");
        assertThat((List<?>) result.result().metrics().get("criticViolations"))
                .extracting(Object::toString).contains("requested_category_coverage");
    }

    private SearchAdsRecsLeadService.Execution decide(String query) {
        return service.decide("priority-" + java.util.UUID.randomUUID(), query, "normal-3c-v1",
                new SearchAdsRecsLeadService.DiscoveryContext("priority-user", "priority-session",
                        false, List.of(), List.of(), List.of()));
    }
}
