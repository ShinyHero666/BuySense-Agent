package com.buysense.agent;

import com.buysense.domain.Requirement;
import com.buysense.platform.DomainPackRegistry;
import com.buysense.platform.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class GroundedIntentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DomainPackRegistry domains = new DomainPackRegistry(mapper, new ExtensionRegistry());
    private final IntentParser parser = new IntentParser(domains);

    @ParameterizedTest
    @CsvSource({"预算七千元买手机,7000", "五千以内的手机,5000", "预算一万两千元买手机,12000",
            "预算三千五百元买手机,3500", "预算两千零五元买手机,2005",
            "预算上限为5000元买手机,5000", "预算0.9万元买手机,9000"})
    void readsUnambiguousBudgets(String query, String amount) {
        assertThat(parser.parse(query).budget()).isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @CsvSource({"预算三千五买手机", "预算三四千买手机", "预算-500元买手机", "预算5000元但预算7000元买手机"})
    void requestsClarificationForConflictingOrAmbiguousAmounts(String query) {
        var requirement = parser.parse(query);
        assertThat(requirement.clarificationQuestions()).isNotEmpty();
    }

    @Test
    void noBudgetIsAllowedAndModelNumbersAreNotMoney() {
        assertThat(parser.parse("推荐手机")).satisfies(r -> {
            assertThat(r.budget()).isNull();
            assertThat(r.clarificationQuestions()).isEmpty();
        });
        assertThat(parser.parse("推荐iPhone15手机").budget()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"不要苹果，推荐手机", "苹果的不要，推荐手机", "不买苹果手机，看看小米"})
    void distinguishesBrandExclusionFromPreference(String query) {
        var requirement = parser.parse(query);
        assertThat(requirement.excludedBrands()).contains("Apple");
        assertThat(requirement.preferredBrands()).doesNotContain("Apple");
    }

    @Test
    void refusalDoesNotCarryAcrossClauses() {
        assertThat(parser.parse("不要苹果，要小米手机").preferredBrands()).containsExactly("Xiaomi");
        assertThat(parser.parse("不要苹果，要小米手机").excludedBrands()).containsExactly("Apple");
    }

    @Test
    void caseDoesNotImplicitlyPurchaseAnotherPhoneAndFastChargingIsNotAnAccessoryRequest() {
        assertThat(parser.parse("推荐手机壳").requiredCategories()).containsExactly("case");
        assertThat(parser.parse("推荐快充手机").requiredCategories()).containsExactly("phone");
        assertThat(parser.parse("推荐快充手机").useCases()).contains("快充");
    }

    @Test
    void modelCanResolveAMissedCategoryOnlyWithSourceEvidence() throws Exception {
        var baseline = parser.parse("想买一个移动电话");
        var proposal = mapper.readTree("""
                {"requestedCategories":["phone"],
                 "evidence":{"requestedCategories":{"phone":"移动电话"}}}
                """);
        var resolved = parser.resolveGrounded(baseline, proposal, domains.require("normal-3c-v1"));
        assertThat(resolved.clarificationQuestions()).isEmpty();
        assertThat(resolved.constraints()).anyMatch(c -> c.field().equals("requestedCategories")
                && c.source() == Requirement.ConstraintSource.INFERRED_MODEL);
        ((com.fasterxml.jackson.databind.node.ObjectNode) proposal).remove("evidence");
        assertThat(parser.resolveGrounded(baseline, proposal, domains.require("normal-3c-v1"))
                .clarificationQuestions()).isNotEmpty();
    }

    @Test
    void modelCannotReplaceExplicitCategoryOrBudget() throws Exception {
        var baseline = parser.parse("预算5000元买手机");
        var proposal = mapper.readTree("""
                {"budgetMax":9000,"requestedCategories":["charger"],
                 "evidence":{"budgetMax":"预算9000元","requestedCategories":{"charger":"手机"}}}
                """);
        var result = parser.resolveGrounded(baseline, proposal, domains.require("normal-3c-v1"));
        assertThat(result.budget()).isEqualByComparingTo("5000");
        assertThat(result.requiredCategories()).containsExactly("phone");
    }

    @Test
    void acceptsGroundedMoneyExtractionButRejectsModelNumberAsBudget() throws Exception {
        var domain = domains.require("normal-3c-v1");
        var proposal = mapper.readTree("""
                {"budgetMax":5000,"evidence":{"budgetMax":"手头只有五千块"}}
                """);
        assertThat(parser.resolveGrounded(parser.parse("手头只有五千块，想买手机"), proposal, domain).budget())
                .isEqualByComparingTo("5000");
        var modelNumber = mapper.readTree("""
                {"budgetMax":15,"evidence":{"budgetMax":"15"}}
                """);
        assertThat(parser.resolveGrounded(parser.parse("想买iPhone15"), modelNumber, domain).budget()).isNull();
    }

    @Test
    void supportsAdvertisingRejectionWithoutConfusingAcceptance() {
        assertThat(parser.parse("付费推广别给我，推荐手机").sponsoredAllowed()).isFalse();
        assertThat(parser.parse("不介意广告，推荐手机").sponsoredAllowed()).isTrue();
    }
}
