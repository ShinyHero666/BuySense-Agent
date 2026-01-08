package com.buysense.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.buysense.domain.Product;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SearchAdsRecsLeadServiceRegressionTest {
    @Autowired
    private SearchAdsRecsLeadService agent;

    @Test
    void keepsChargingPowerAndProtocolEvidenceInTheGameBundle() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-3c-game-fast-charge",
                "预算5000元，帮我搭配一套偏游戏和快充的手机、耳机、充电器",
                "normal-3c-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().criticVerdict()).isEqualTo("approved");
        Product phone = execution.result().bundles().get(0).items().stream()
                .filter(item -> item.category().equals("phone"))
                .findFirst().orElseThrow();
        Product charger = execution.result().bundles().get(0).items().stream()
                .filter(item -> item.category().equals("charger"))
                .findFirst().orElseThrow();
        assertThat(phone.maxPowerWatts()).isNotNull();
        assertThat(charger.maxPowerWatts())
                .isGreaterThanOrEqualTo((int) Math.ceil(phone.maxPowerWatts() * 0.8));
        assertThat(execution.result().runtime().explanation())
                .contains("快充依据：", "最大输出", "协议");
    }

    @Test
    void explainsExplicitProtocolRequirementsFromStructuredCatalogFields() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-en417-evidence",
                "预算100元，不要广告，EN417气罐",
                "outdoor-camping-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().explanation())
                .contains("接口协议依据：", "EN417 螺纹接口", "Lindal 阀门");
    }

    @Test
    void explainsAnInsufficientBudgetWithoutLeakingInternalAuditCodes() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-insufficient-phone-budget",
                "手机预算只有2000元，给我推荐一款",
                "normal-3c-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().criticVerdict()).isEqualTo("vetoed");
        assertThat(execution.result().runtime().explanation())
                .contains(
                        "当前没有生成可确认的购买方案",
                        "未找到符合要求的主商品",
                        "当前预算上限为¥2000",
                        "可以提高预算，或放宽品牌、容量、性能等要求后重试")
                .doesNotContain("has_primary_product", "requested_category_coverage");
    }

    @Test
    void doesNotMislabelAnOrganicAdsOverlapAsSponsored() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-camping-sponsored",
                "预算500元，搭配一套双人便携炉具、气罐和锅具",
                "outdoor-camping-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().explanation()).doesNotContain("｜赞助");
    }

    @Test
    void publishesOnlyContractStatesWithTaskLineageAndARevisionCritiqueWhenNeeded() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-artifact-lineage",
                "recommend a phone",
                "normal-3c-v1",
                emptyDiscoveryContext());

        assertThat(execution.artifacts()).isNotEmpty();
        assertThat(execution.artifacts())
                .allMatch(artifact -> Set.of("draft", "verified", "vetoed")
                        .contains(artifact.status().value()))
                .allMatch(artifact -> artifact.parentTaskId() != null
                        && !artifact.parentTaskId().isBlank());
        assertThat(execution.artifacts())
                .extracting(AgentArtifact::type)
                .contains(
                        "retrieval_plan",
                        "candidate_set",
                        "bundle_proposal",
                        "price_quote",
                        "review_evidence",
                        "critique",
                        "final_decision");
        assertThat(execution.artifacts().stream()
                .filter(artifact -> artifact.type().equals("bundle_proposal"))
                .map(artifact -> artifact.status().value()))
                .containsOnly("draft");
        long critiqueCount = execution.artifacts().stream()
                .filter(artifact -> artifact.type().equals("critique"))
                .count();
        assertThat(critiqueCount).isEqualTo(execution.revisionApplied() ? 2 : 1);
        String expectedFinalState = "approved".equals(execution.result().runtime().criticVerdict())
                ? "verified" : "vetoed";
        assertThat(execution.artifacts().stream()
                .filter(artifact -> artifact.type().equals("final_decision"))
                .map(artifact -> artifact.status().value()))
                .containsExactly(expectedFinalState);
    }

    @Test
    void preservesTheFullChainRoleSetAndDecisionEvidenceArtifacts() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-full-chain",
                "budget 7000, bundle a camera phone, earphones and charger",
                "normal-3c-v1",
                emptyDiscoveryContext());

        assertThat(execution.roleCalls())
                .extracting(ModelPortAgentBridge.RoleCall::role)
                .containsExactlyInAnyOrder(
                        "intent_router", "search", "recommendation", "ads", "critic", "lead");
        assertThat(execution.tasks())
                .extracting(BoundedCollaborationCoordinator.Task::capability)
                .contains(
                        "understand_and_route",
                        "search_strategy_and_retrieval",
                        "recommendation_strategy_and_retrieval",
                        "ads_strategy_and_retrieval",
                        "calibrated_candidate_fusion",
                        "constraint_bundle_optimization",
                        "live_quote_tool",
                        "review_aspect_tool",
                        "independent_decision_audit",
                        "grounded_response_composition");

        AgentArtifact fused = execution.artifacts().stream()
                .filter(value -> value.type().equals("candidate_set"))
                .filter(value -> value.producer().equals("lead"))
                .findFirst().orElseThrow();
        assertThat(fused.payload()).containsKeys("channel", "candidates");
        assertThat((List<?>) fused.payload().get("candidates")).isNotEmpty();

        AgentArtifact bundle = execution.artifacts().stream()
                .filter(value -> value.type().equals("bundle_proposal"))
                .findFirst().orElseThrow();
        assertThat(bundle.payload()).containsKeys(
                "items", "totalPrice", "budgetMax", "withinBudget",
                "compatibility", "score", "optimization", "alternatives");
        assertThat((List<?>) bundle.payload().get("items")).isNotEmpty();

        AgentArtifact quotes = execution.artifacts().stream()
                .filter(value -> value.type().equals("price_quote"))
                .findFirst().orElseThrow();
        assertThat(quotes.payload()).containsKeys(
                "quote_batch_id", "quote_version", "issued_at", "data_source", "quotes");
        assertThat((List<?>) quotes.payload().get("quotes")).isNotEmpty();

        AgentArtifact reviews = execution.artifacts().stream()
                .filter(value -> value.type().equals("review_evidence"))
                .findFirst().orElseThrow();
        assertThat(reviews.payload()).containsKeys(
                "review_snapshot_version", "data_source", "products", "missing_product_ids");
        assertThat((List<?>) reviews.payload().get("products")).isNotEmpty();

        assertThat(execution.result().trace()).anySatisfy(step -> {
            assertThat(step.stage()).isEqualTo("pricing");
            assertThat(step.decision()).isEqualTo("data_plane_result");
        }).anySatisfy(step -> {
            assertThat(step.stage()).isEqualTo("review_evidence");
            assertThat(step.decision()).isEqualTo("data_plane_result");
        });
    }

    @Test
    void photoBundleExplainsConcreteCatalogAndReviewEvidence() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-photo-evidence",
                "预算7000元，不要广告，帮我搭配一套适合拍照的手机、耳机和充电器",
                "normal-3c-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().criticVerdict()).isEqualTo("approved");
        assertThat(execution.result().runtime().explanation())
                .contains(
                        "拍照：目录证据",
                        "评论聚合（样本量",
                        "提及",
                        "证据来源：",
                        "review-aspects-v2")
                .doesNotContain("未覆盖线下手感与长期耐久差异");
    }

    @Test
    void comparisonCoversPhotoAndBatteryWithAnExplicitConclusion() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-phone-comparison-evidence",
                "预算5000元，不要广告，比较几款重视拍照和续航的手机",
                "normal-3c-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().criticVerdict()).isEqualTo("approved");
        assertThat(execution.result().runtime().explanation())
                .contains(
                        "对比候选：",
                        "拍照：",
                        "续航：",
                        "样本量",
                        "对比结论（仅按当前证据快照）",
                        "不等同于实验室性能排名")
                .doesNotContain("商品快照与评价证据完整");
    }

    @Test
    void preservesSuffixBudgetExpressionsAsHardConstraints() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-suffix-budget",
                "5000元内找游戏和续航表现好的手机",
                "normal-3c-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().requirement().budget()).isEqualByComparingTo("5000");
        assertThat(execution.result().slate())
                .allMatch(candidate -> candidate.product().price().compareTo(
                        execution.result().requirement().budget()) <= 0);
    }

    @Test
    void keepsDeterministicCatalogBaselineForNeedOnlySearch() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-need-based-intent",
                "500元内，不要广告，找一款双人便携卡式炉",
                "outdoor-camping-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().intent()).isEqualTo("catalog");
        assertThat(execution.result().requirement().useCases()).contains("双人", "便携");
    }
    @Test
    void streamsEveryTraceInOrderWithAuditableArtifactPayloads() {
        List<com.buysense.domain.DecisionResult.TraceStep> observed =
                new CopyOnWriteArrayList<>();

        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-live-trace",
                "budget 7000 bundle camera phone earphones charger",
                "normal-3c-v1",
                emptyDiscoveryContext(),
                () -> false,
                observed::add);

        assertThat(observed)
                .containsExactlyElementsOf(execution.result().trace());
        assertThat(observed)
                .filteredOn(step -> step.decision().contains("artifact"))
                .isNotEmpty()
                .allSatisfy(step -> assertThat(step.facts())
                        .containsKeys(
                                "artifactId",
                                "artifactType",
                                "status",
                                "artifactPayload",
                                "parentTaskId"));
        assertThat(execution.result().runtime().roleExecutions())
                .extracting(com.buysense.domain.DecisionResult.RoleExecution::role)
                .containsExactlyInAnyOrder(
                        "intent_router", "search", "recommendation", "ads", "critic", "lead");
        assertThat(execution.result().runtime().roleExecutions())
                .allSatisfy(role -> {
                    assertThat(role.provider()).isNotBlank();
                    assertThat(role.model()).isNotBlank();
                });
        assertThat(execution.result().runtime().localOnly()).isTrue();
        assertThat(execution.tasks())
                .allSatisfy(task -> assertThat(task.depth()).isEqualTo(1));

        List<com.buysense.domain.DecisionResult.TraceStep> trace =
                execution.result().trace();
        for (AgentArtifact artifact : execution.artifacts()) {
            int completionIndex = -1;
            int publicationIndex = -1;
            for (int index = 0; index < trace.size(); index++) {
                com.buysense.domain.DecisionResult.TraceStep step = trace.get(index);
                if (java.util.Objects.equals(
                        step.facts().get("artifactId"),
                        artifact.artifactId())) {
                    publicationIndex = index;
                }
                if ("task_completed".equals(step.decision())
                        && java.util.Objects.equals(
                                step.facts().get("taskId"),
                                artifact.parentTaskId())) {
                    completionIndex = index;
                }
            }
            if (completionIndex < 0) continue;
            assertThat(publicationIndex)
                    .as("artifact %s must be published inside its task", artifact.artifactId())
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(completionIndex);
        }

    }

    @Test
    void keepsExplicitRecommendationAheadOfProtocolAndBrowsingAheadOfSoftUseCases() {
        SearchAdsRecsLeadService.Execution recommendation = agent.decide(
                "regression-recommendation-before-protocol",
                "预算300元，不要广告，推荐USB-C数据线",
                "normal-3c-v1",
                emptyDiscoveryContext());
        SearchAdsRecsLeadService.Execution browsing = agent.decide(
                "regression-browse-before-soft-use-case",
                "不要广告，看看轻量钛锅",
                "outdoor-camping-v1",
                emptyDiscoveryContext());

        assertThat(recommendation.result().runtime().intent()).isEqualTo("exploratory");
        assertThat(browsing.result().runtime().intent()).isEqualTo("catalog");
    }

    private SearchAdsRecsLeadService.DiscoveryContext emptyDiscoveryContext() {
        return new SearchAdsRecsLeadService.DiscoveryContext(
                "regression-user",
                "regression-session",
                false,
                List.of(),
                List.of(),
                List.of());
    }
}
