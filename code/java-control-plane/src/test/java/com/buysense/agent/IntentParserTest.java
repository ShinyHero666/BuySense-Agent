package com.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.buysense.domain.Requirement.ConstraintSource;
import com.buysense.domain.Requirement.ConstraintStrength;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.platform.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    void capturesExplicitProtocolTermsAsHardConstraints() {
        var requirement = parser.parse(
                "预算100元，不要广告，EN417气罐",
                "outdoor-camping-v1");

        assertThat(requirement.constraints()).anyMatch(constraint ->
                constraint.field().equals("protocolTerms")
                        && constraint.value().equals("en417")
                        && constraint.source() == ConstraintSource.EXPLICIT_USER
                        && constraint.strength() == ConstraintStrength.HARD);
    }

    @Test
    void keepsAdOptOutAsTheAuthoritativePlanPolicy() {
        var requirement = parser.parse("预算5000元，不要广告，推荐拍照手机");

        assertThat(requirement.sponsoredAllowed()).isFalse();
        assertThat(requirement.requiredCategories()).containsExactly("phone");
        assertThat(requirement.constraints())
                .noneMatch(constraint -> constraint.field().equals("sponsored"));
    }

    @ParameterizedTest
    @CsvSource({
            "'预算5000元，推荐拍照手机', 5000",
            "'我有5000元预算，想比较几款手机', 5000",
            "'5000元内找游戏手机', 5000",
            "'预算上限为¥5,000，推荐手机', 5000",
            "'预算5k，推荐手机', 5000",
            "'0.9万元内推荐手机', 9000"
    })
    void recognizesCommonExplicitBudgetExpressions(String message, String expected) {
        assertThat(parser.parse(message).budget()).isEqualByComparingTo(expected);
    }

    @Test
    void doesNotTreatAModelNumberAsABudget() {
        assertThat(parser.parse("找一台型号为R7000的设备").budget()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "不接受广告，推荐拍照手机",
            "关闭广告，只看自然结果，推荐拍照手机",
            "不要赞助，推荐拍照手机"
    })
    void recognizesExplicitAdvertisingOptOutExpressions(String message) {
        assertThat(parser.parse(message).sponsoredAllowed()).isFalse();
    }

    @Test
    void normalizesDomainConfiguredUseCaseAliases() {
        var requirement = parser.parse(
                "预算4.5k，找轻便、续航好、适合旅行的手机");

        assertThat(requirement.useCases())
                .containsExactly("续航", "便携", "旅行");
        assertThat(requirement.constraints()).anyMatch(constraint ->
                constraint.field().equals("useCases")
                        && constraint.value().equals("便携")
                        && constraint.source() == ConstraintSource.EXPLICIT_USER);
    }

    @Test
    void doesNotInventACatalogCapabilityAsAnExplicitUseCase() {
        var requirement = parser.parse(
                "¥900上限，以SummitFlow高山露营炉为核心，搭配兼容的气罐和锅具，"
                        + "主要在低温高海拔使用。",
                "outdoor-camping-v1");

        assertThat(requirement.preferredBrands()).containsExactly("SummitFlow");
        assertThat(requirement.useCases()).containsExactly("高海拔", "低温");
        assertThat(requirement.useCases()).doesNotContain("稳压");
    }
}
