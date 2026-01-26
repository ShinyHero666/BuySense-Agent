package com.moyuan.buysense.domain;

import java.util.List;
import java.util.Map;

public record Candidate(
        Product product,
        double score,
        boolean sponsored,
        List<String> channels,
        Map<String, Double> channelScores,
        List<String> reasons
) {
}
