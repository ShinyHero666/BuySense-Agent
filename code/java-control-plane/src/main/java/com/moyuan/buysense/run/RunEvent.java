package com.moyuan.buysense.run;

import java.time.Instant;
import java.util.Map;

public record RunEvent(
        String eventId,
        long sequence,
        String eventType,
        Instant timestamp,
        Map<String, Object> payload
) {
}
