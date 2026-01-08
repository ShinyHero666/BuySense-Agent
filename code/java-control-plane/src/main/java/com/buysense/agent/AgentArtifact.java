package com.buysense.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, lineage-aware output produced by one bounded Agent task. */
public record AgentArtifact(
        String artifactId,
        String runId,
        String parentTaskId,
        String type,
        String producer,
        Status status,
        Map<String, Object> payload,
        Instant createdAt
) {
    public AgentArtifact {
        artifactId = requireText(artifactId, "artifactId");
        runId = requireText(runId, "runId");
        type = requireText(type, "type");
        producer = requireText(producer, "producer");
        status = Objects.requireNonNull(status, "status");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public AgentArtifact withStatus(Status value) {
        return new AgentArtifact(
                artifactId, runId, parentTaskId, type, producer, value, payload, createdAt);
    }

    public enum Status {
        DRAFT("draft"),
        VERIFIED("verified"),
        VETOED("vetoed");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
