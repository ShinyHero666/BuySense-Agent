package com.buysense.evaluation;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvaluationSuiteLoaderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EvaluationSuiteLoader loader = new EvaluationSuiteLoader(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0", "3.0"})
    void acceptsSupportedSchemaVersions(String schemaVersion) throws Exception {
        EvaluationSuite suite = mapper.readValue(suiteJson(schemaVersion), EvaluationSuite.class);

        assertThatNoException().isThrownBy(() -> loader.validate(suite));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.9", "4.0"})
    void rejectsUnsupportedSchemaVersions(String schemaVersion) throws Exception {
        EvaluationSuite suite = mapper.readValue(suiteJson(schemaVersion), EvaluationSuite.class);

        assertThatThrownBy(() -> loader.validate(suite))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    private static String suiteJson(String schemaVersion) {
        return """
                {
                  "schemaVersion": "%s",
                  "suiteId": "loader-test",
                  "systemVersion": "test",
                  "cases": [{
                    "caseId": "case-1",
                    "domainPackId": "3c",
                    "prompt": "测试",
                    "expected": {"expectedVerdict": "approved"}
                  }]
                }
                """.formatted(schemaVersion);
    }
}
