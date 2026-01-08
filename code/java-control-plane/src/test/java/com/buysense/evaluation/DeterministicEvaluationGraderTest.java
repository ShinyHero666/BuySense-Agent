package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeterministicEvaluationGraderTest {
    @Test
    void recognizesEquivalentFailClosedWording() {
        assertThat(DeterministicEvaluationGrader.explicitlyNonConfirmable(
                "当前无法给出可确认的购买方案：所需商品品类未配齐。"))
                .isTrue();
        assertThat(DeterministicEvaluationGrader.explicitlyNonConfirmable(
                "没有生成可确认的购物车草案。"))
                .isTrue();
    }

    @Test
    void doesNotTreatOrdinaryRecommendationCopyAsFailClosed() {
        assertThat(DeterministicEvaluationGrader.explicitlyNonConfirmable(
                "以下是可供比较的商品建议，请确认后再购买。"))
                .isFalse();
        assertThat(DeterministicEvaluationGrader.explicitlyNonConfirmable(null))
                .isFalse();
    }
}
