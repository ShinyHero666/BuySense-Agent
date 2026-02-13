package com.moyuan.buysense.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
        assertThat(execution.result().runtime().explanation())
                .contains("快充依据：", "最大输出", "协议");
    }

    @Test
    void keepsSponsoredDisclosureWhenASponsoredBundleItemIsSelected() {
        SearchAdsRecsLeadService.Execution execution = agent.decide(
                "regression-camping-sponsored",
                "预算500元，搭配一套双人便携炉具、气罐和锅具",
                "outdoor-camping-v1",
                emptyDiscoveryContext());

        assertThat(execution.result().runtime().explanation()).contains("｜赞助");
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
