package com.moyuan.buysense.run;

import java.time.Instant;
import java.util.Map;

public record RunEvent(
        String eventId,
        long sequence,
        String eventType,
        Instant timestamp,
        String schemaVersion,
        Map<String, Object> payload
) {
    public RunEvent(
            String eventId,
            long sequence,
            String eventType,
            Instant timestamp,
            Map<String, Object> payload
    ) {
        this(eventId, sequence, eventType, timestamp, "2.0", payload);
    }
}