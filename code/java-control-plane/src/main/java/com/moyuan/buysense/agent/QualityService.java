package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Serves the committed, reproducible BuySense retrieval report. */
@Service
public class QualityService {
    private final JsonNode report;

    public QualityService(ObjectMapper mapper) {
        try (var input = new ClassPathResource("data/v2/retrieval_report.json").getInputStream()) {
            this.report = mapper.readTree(input);
        } catch (IOException error) {
            throw new IllegalStateException("failed to load retrieval report", error);
        }
    }

    public JsonNode report() {
        return report;
    }
}
