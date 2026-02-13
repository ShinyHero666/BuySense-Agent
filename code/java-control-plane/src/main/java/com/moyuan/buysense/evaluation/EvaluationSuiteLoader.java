package com.moyuan.buysense.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public final class EvaluationSuiteLoader {
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
        if (suite == null || !"1.0".equals(suite.schemaVersion())) {
            throw new IllegalArgumentException("evaluation suite schemaVersion must be 1.0");
        }
        if (blank(suite.suiteId()) || blank(suite.systemVersion()) || suite.cases().isEmpty()) {
            throw new IllegalArgumentException("evaluation suite metadata or cases are missing");
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
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
