package com.buysense.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public final class EvaluationSuiteLoader {
    private static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("1.0", "2.0", "3.0");

    private final ObjectMapper mapper;

    public EvaluationSuiteLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EvaluationSuite loadClasspath(String resourcePath) {
        String normalized = resourcePath.startsWith("/")
                ? resourcePath.substring(1)
                : resourcePath;
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(normalized)) {
            if (stream == null) {
                throw new IllegalArgumentException("evaluation suite not found: " + resourcePath);
            }
            EvaluationSuite suite = mapper.readValue(stream, EvaluationSuite.class);
            validate(suite);
            return suite;
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read evaluation suite: " + resourcePath, error);
        }
    }

    public void validate(EvaluationSuite suite) {
        if (suite == null || !SUPPORTED_SCHEMA_VERSIONS.contains(suite.schemaVersion())) {
            throw new IllegalArgumentException(
                    "evaluation suite schemaVersion must be one of " + SUPPORTED_SCHEMA_VERSIONS);
        }
        if (blank(suite.suiteId()) || blank(suite.systemVersion()) || suite.cases().isEmpty()) {
            throw new IllegalArgumentException("evaluation suite metadata or cases are missing");
        }
        if (suite.protocol().partition() == EvaluationSuite.Partition.CAPABILITY_HOLDOUT
                && suite.protocol().promptTuningAllowed()) {
            throw new IllegalArgumentException("capability holdout cannot be used for prompt tuning");
        }
        if (suite.protocol().hiddenLabels()
                && (!suite.protocol().taskSetSha256().matches("[0-9a-f]{64}")
                || !suite.protocol().goldSetSha256().matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("hidden benchmark resources require SHA-256 fingerprints");
        }
        Set<String> ids = new HashSet<>();
        for (EvaluationCase value : suite.cases()) {
            if (blank(value.caseId()) || !ids.add(value.caseId())) {
                throw new IllegalArgumentException("evaluation case id is blank or duplicated: " + value.caseId());
            }
            if (blank(value.domainPackId()) || blank(value.prompt()) || value.expected() == null) {
                throw new IllegalArgumentException("evaluation case is incomplete: " + value.caseId());
            }
            if (value.trials() > 5) {
                throw new IllegalArgumentException("evaluation case trials must be between 1 and 5: " + value.caseId());
            }
            String verdict = value.expected().expectedVerdict();
            if (verdict != null && !Set.of("approved", "vetoed").contains(verdict)) {
                throw new IllegalArgumentException("unsupported expected verdict: " + verdict);
            }
            Set<String> rubricIds = new HashSet<>();
            for (EvaluationCase.JudgeRubric rubric : value.judgeRubrics()) {
                if (blank(rubric.rubricId()) || blank(rubric.criterion())
                        || rubric.minimumScore() < 1
                        || rubric.minimumScore() > 5
                        || !rubricIds.add(rubric.rubricId())) {
                    throw new IllegalArgumentException("invalid judge rubric: " + value.caseId());
                }
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
