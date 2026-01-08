package com.buysense.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.buysense.domain.Requirement;
import com.buysense.platform.CommerceDomainPack;
import com.buysense.platform.DomainPackRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RetrievalPlanPolicyParityTest {
    @Autowired
    private SearchAdsRecsLeadService service;

    @Autowired
    private IntentParser parser;

    @Autowired
    private DomainPackRegistry domains;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void preservesDeterministicUserConstraintsAndCanonicalizesModelProposal() {
        String message = "预算7000元，不要广告，帮我搭配拍照手机、耳机和充电器";
        CommerceDomainPack domain = domains.require("normal-3c-v1");
        Requirement baseline = parser.parse(message, domain.packId());
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("intent", "exploratory");
        proposal.put("query", "  拍照   套装  ");
        proposal.putArray("requestedCategories").add("phone").add("unsupported");
        proposal.putArray("preferredBrands").add("invented-brand");
        proposal.putArray("useCases").add("游戏");
        proposal.putArray("channels").add("ads").add("search");
        proposal.put("sponsoredAllowed", true);
        ObjectNode budgets = proposal.putObject("candidateBudget");
        budgets.put("search", 20.6);
        budgets.put("recommendation", 2.6);
        budgets.put("ads", "four");
        proposal.put("reason", 1);

        SearchAdsRecsLeadService.PlanResolution resolved =
                service.resolvePlanProposal(
                        "plan-policy-hard-boundaries",
                        message,
                        domain,
                        emptyDiscoveryContext(),
                        baseline,
                        proposal);
        SearchAdsRecsLeadService.Plan plan = resolved.plan();

        assertThat(plan.intent()).isEqualTo("bundle");
        assertThat(plan.requirement().retrievalQuery()).isEqualTo("拍照 套装");
        assertThat(plan.requirement().budget()).isEqualByComparingTo("7000");
        assertThat(plan.requirement().requiredCategories())
                .containsExactlyElementsOf(baseline.requiredCategories());
        assertThat(plan.requirement().preferredBrands())
                .containsExactlyElementsOf(baseline.preferredBrands());
        assertThat(plan.requirement().useCases()).contains("拍照", "游戏");
        assertThat(plan.requirement().sponsoredAllowed()).isFalse();
        assertThat(plan.channels()).containsExactly("search", "recommendation");
        assertThat(plan.candidateBudget())
                .containsEntry("search", 12)
                .containsEntry("recommendation", 3)
                .containsEntry("ads", 4);
        assertThat(resolved.corrections()).containsExactly(
                "explicit_intent_preserved",
                "unsupported_category_removed",
                "explicit_category_scope_preserved",
                "explicit_use_case_preserved",
                "ungrounded_brand_preference_removed",
                "ad_opt_out_enforced",
                "bundle_recommendation_channel_restored",
                "ads_channel_removed",
                "search_candidate_budget_clamped",
                "recommendation_candidate_budget_clamped",
                "ads_candidate_budget_invalid",
                "invalid_reason");
    }

    @Test
    void modelAddedSoftUseCaseDoesNotRewriteTheDeterministicBaselineIntent() {
        String message = "看看手机";
        CommerceDomainPack domain = domains.require("normal-3c-v1");
        Requirement baseline = parser.parse(message, domain.packId());
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("intent", "precise");
        proposal.putArray("useCases").add("游戏");
        proposal.putArray("channels").add("ads").add("recommendation").add("search");

        SearchAdsRecsLeadService.PlanResolution resolved =
                service.resolvePlanProposal(
                        "plan-policy-order",
                        message,
                        domain,
                        emptyDiscoveryContext(),
                        baseline,
                        proposal);

        assertThat(resolved.plan().intent()).isEqualTo("catalog");
        assertThat(resolved.plan().channels())
                .containsExactly("search", "recommendation", "ads");
        assertThat(resolved.plan().requirement().useCases()).contains("游戏");
        assertThat(resolved.corrections()).contains("explicit_intent_preserved");
    }

    private SearchAdsRecsLeadService.DiscoveryContext emptyDiscoveryContext() {
        return new SearchAdsRecsLeadService.DiscoveryContext(
                "policy-user", "policy-session", false,
                List.of(), List.of(), List.of());
    }
}
