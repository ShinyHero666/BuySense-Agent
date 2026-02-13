package com.moyuan.buysense.run;

import java.time.Instant;
import java.util.Map;

public record RunEvent(
        String eventId,
        String runId,
        String taskId,
        String parentTaskId,
        long sequence,
        String eventType,
        Instant timestamp,
        String schemaVersion,
        Map<String, Object> payload
) {
    public RunEvent(
            String eventId,
            String runId,
            String taskId,
            String parentTaskId,
            long sequence,
            String eventType,
            Instant timestamp,
            Map<String, Object> payload
    ) {
        this(eventId, runId, taskId, parentTaskId, sequence, eventType,
                timestamp, "2.0", payload);
    }

    public RunEvent(
            String eventId,
            String runId,
            long sequence,
            String eventType,
            Instant timestamp,
            Map<String, Object> payload
    ) {
        this(eventId, runId, value(payload, "taskId"), value(payload, "parentTaskId"),
                sequence, eventType, timestamp, "2.0", payload);
    }

    /** Convenience constructor retained for isolated unit fixtures. */
    public RunEvent(
            String eventId,
            long sequence,
            String eventType,
            Instant timestamp,
            Map<String, Object> payload
    ) {
        this(eventId, "fixture-run", sequence, eventType, timestamp, payload);
    }

    private static String value(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
