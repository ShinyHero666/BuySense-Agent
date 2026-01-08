package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buysense.evaluation.EvaluationSuite.Partition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BenchmarkPartitionLoaderTest {
    private static final String TASKS =
            "evaluation/fixtures/benchmark_partition_fixture.tasks.json";
    private static final String GOLD =
            "evaluation/fixtures/benchmark_partition_fixture.gold.json";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final BenchmarkPartitionLoader loader = new BenchmarkPartitionLoader(mapper);

    @Test
    void keepsPublicTasksSeparateFromHiddenGradingContracts() throws Exception {
        EvaluationSuite suite = loader.loadClasspath(TASKS, GOLD);

        assertThat(suite.schemaVersion()).isEqualTo("3.0");
        assertThat(suite.protocol().partition()).isEqualTo(Partition.CAPABILITY_HOLDOUT);
        assertThat(suite.protocol().promptTuningAllowed()).isFalse();
        assertThat(suite.protocol().hiddenLabels()).isTrue();
        assertThat(suite.protocol().taskSetSha256())
                .matches("[0-9a-f]{64}");
        assertThat(suite.protocol().goldSetSha256())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(suite.protocol().taskSetSha256());
        assertThat(suite.cases()).hasSize(1)
                .allSatisfy(value -> {
                    assertThat(value.trials()).isEqualTo(3);
                    assertThat(value.expected()).isNotNull();
                    assertThat(value.judgeRubrics()).isNotEmpty();
                });

        String publicTasks = resource(TASKS);
        assertThat(publicTasks)
                .doesNotContain("\"expected\"")
                .doesNotContain("\"judgeRubrics\"")
                .doesNotContain("\"goldSetId\"");
        assertThat(resource(GOLD)).doesNotContain("\"prompt\"");

        EvaluationSuite replay = loader.loadClasspath(TASKS, GOLD);
        assertThat(replay.protocol().taskSetSha256())
                .isEqualTo(suite.protocol().taskSetSha256());
        assertThat(replay.protocol().goldSetSha256())
                .isEqualTo(suite.protocol().goldSetSha256());
    }

    @Test
    void rejectsAResourceThatIsNotTheMatchingGoldContract() {
        assertThatThrownBy(() -> loader.loadClasspath(TASKS, TASKS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String resource(String path) throws Exception {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
