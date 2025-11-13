package com.buysense.evaluation;

import com.buysense.agent.SearchAdsRecsLeadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "BUYSENSE_PRIORITY_FROZEN_REGRESSION", matches = "true")
class PriorityFrozenTaskRegressionTest {
    @Autowired private ObjectMapper mapper;
    @Autowired private SearchAdsRecsLeadService agent;

    @Test
    void replaysEveryExistingV2TaskWithoutPretendingToBeAnIndependentJudge() {
        EvaluationSuite original = new BenchmarkPartitionLoader(mapper).loadClasspath(
                "evaluation/buysense_capability_holdout_v2.tasks.json",
                "evaluation/private/buysense_capability_holdout_v2.gold.json");
        EvaluationReport originalReport = new EvaluationHarness("priority-v3-original-contract", agent::decide,
                new DeterministicEvaluationGrader(), EvaluationModelJudge.disabled()).run(original);
        EvaluationReportWriter writer = new EvaluationReportWriter(mapper);
        writer.writeJson(originalReport, Path.of("target/evaluation/priority-frozen-v2/original-contract.json"));
        // Preserve original gold and publish a separately named regression contract for the new policy.
        var cases = original.cases().stream().map(value -> {
            com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(value);
            var expected = (com.fasterxml.jackson.databind.node.ObjectNode) json.path("expected");
            expected.remove("minBudgetUtilization");
            var audits = (com.fasterxml.jackson.databind.node.ArrayNode) expected.path("requiredAuditChecks");
            for (int i = 0; i < audits.size(); i++) {
                if (audits.get(i).asText().equals("budget_utilization_reasonable")) audits.set(i,
                        mapper.getNodeFactory().textNode("budget_respected"));
            }
            if (value.caseId().equals("holdout-v2-camp-summitflow-bundle")) {
                // The prompt names a brand, not an explicit pressure-regulation preference.
                expected.putArray("expectedUseCases").add("高海拔").add("低温");
            }
            return mapper.convertValue(json, EvaluationCase.class);
        }).toList();
        EvaluationSuite suite = new EvaluationSuite("3.0", "priority-v3-existing-task-regression",
                "Existing v2 prompts; budget-ceiling audit and explicit-intent label corrections. "
                        + "This is regression, not a new blind holdout.", "priority-policy-v3", cases);
        try {
            java.nio.file.Files.writeString(Path.of("target/evaluation/priority-frozen-v2/regression-contract.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(suite));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
        EvaluationReport report = new EvaluationHarness("priority-v3-local-regression", agent::decide,
                new DeterministicEvaluationGrader(), EvaluationModelJudge.disabled()).run(suite);
        writer.writeJson(report, Path.of("target/evaluation/priority-frozen-v2/report.json"));
        writer.writeMarkdown(report, Path.of("target/evaluation/priority-frozen-v2/report.md"));
        assertThat(report.aggregate().caseCount()).isEqualTo(12);
        assertThat(report.aggregate().trialCount()).isEqualTo(36);
        assertThat(report.aggregate().redlinePassRate()).isEqualTo(1.0);
        assertThat(report.aggregate().taskSuccessRate()).isEqualTo(1.0);
        assertThat(report.modelJudgments()).isNotEmpty().allSatisfy(judgment -> {
            assertThat(judgment.label()).isEqualTo(EvaluationModelJudge.Label.UNKNOWN);
            assertThat(judgment.totalTokens()).isZero();
        });
    }
}
