package com.buysense.agent;

import com.buysense.platform.DomainPackRegistry;
import com.buysense.retail.ReviewEvidenceGateway;
import com.buysense.sar.data.JavaRetailDataPlane;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class CriticRevisionRegressionTest {
    @Autowired private ObjectMapper mapper;
    @Autowired private IntentParser parser;
    @Autowired private DomainPackRegistry domains;
    @Autowired private JavaRetailDataPlane data;
    @Autowired private ReviewEvidenceGateway reviews;

    @org.junit.jupiter.api.Test
    void configuredCallBudgetAppliesToTheWholeWorkflow() {
        ModelPortProperties properties = new ModelPortProperties();
        properties.setMaxCallsPerRun(1);
        AtomicInteger attempts = new AtomicInteger();
        ModelPortAgentBridge limitedModel = new ModelPortAgentBridge(properties, mapper) {
            @Override public String mode() { return "test-model"; }
            @Override public AgentModelTransport.Completion complete(AgentModelTransport.Request request) {
                attempts.incrementAndGet();
                return new AgentModelTransport.Completion(null,
                        List.of(new AgentModelTransport.ToolCall("publish", "publish_artifact",
                                mapper.createObjectNode().put("payload", "{}"))), AgentModelTransport.Usage.ZERO);
            }
        };
        var service = new SearchAdsRecsLeadService(parser, domains, data, reviews, limitedModel, mapper, Runnable::run);
        var result = service.decide("configured-budget", "推荐拍照手机，不要广告", "normal-3c-v1",
                new SearchAdsRecsLeadService.DiscoveryContext("user", "session", false,
                        List.of(), List.of(), List.of())).result();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(result.runtime().modelCalls()).isEqualTo(1);
        assertThat(result.runtime().criticVerdict()).isEqualTo("approved");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revisionRechecksRatherThanForgetsPriorEvidenceIssues(boolean evidenceRecovers) {
        JavaRetailDataPlane faultyFirstBundle = spy(data);
        AtomicInteger bundleCalls = new AtomicInteger();
        doAnswer(call -> {
            ObjectNode result = mapper.valueToTree(data.bundles(call.getArgument(0), call.getArgument(1)));
            if (bundleCalls.incrementAndGet() == 1) {
                ObjectNode first = (ObjectNode) result.path("bundles").get(0);
                ArrayNode ids = (ArrayNode) first.path("sku_ids");
                ids.remove(ids.size() - 1);
            }
            return mapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
        }).when(faultyFirstBundle).bundles(anyString(), any(JsonNode.class));
        ReviewEvidenceGateway controlledReviews = spy(reviews);
        AtomicInteger reviewCalls = new AtomicInteger();
        doAnswer(call -> {
            ObjectNode result = reviews.reviews(call.getArgument(0), call.getArgument(1)).deepCopy();
            double confidence = reviewCalls.incrementAndGet() > 1 && evidenceRecovers ? 0.9 : 0.4;
            result.path("products").forEach(product -> product.path("aspects").forEach(aspect ->
                    ((ObjectNode) aspect).put("confidence", confidence)));
            return result;
        }).when(controlledReviews).reviews(anyString(), anyList());
        AtomicInteger criticCalls = new AtomicInteger();
        ModelPortAgentBridge model = new ModelPortAgentBridge(new ModelPortProperties(), mapper) {
            @Override public String mode() { return "test-model"; }
            @Override public AgentModelTransport.Completion complete(AgentModelTransport.Request request) {
                String payload = "{}";
                if (request.role().equals("critic")) {
                    criticCalls.incrementAndGet();
                    payload = "{\"verdict\":\"vetoed\",\"additionalViolations\":[\"insufficient_review_confidence\"]}";
                }
                return new AgentModelTransport.Completion(null,
                        List.of(new AgentModelTransport.ToolCall("publish-" + request.role(),
                                "publish_artifact", mapper.createObjectNode().put("payload", payload))),
                        AgentModelTransport.Usage.ZERO);
            }
        };
        SearchAdsRecsLeadService service = new SearchAdsRecsLeadService(parser, domains, faultyFirstBundle,
                controlledReviews, model, mapper, Runnable::run);
        var result = service.decide("revision-" + evidenceRecovers,
                "预算100000元，搭配手机、耳机和充电器，不要广告", "normal-3c-v1",
                new SearchAdsRecsLeadService.DiscoveryContext("reviewer", "session", false,
                        List.of(), List.of(), List.of())).result();
        assertThat(result.runtime().revisionApplied()).isTrue();
        assertThat(criticCalls.get()).isEqualTo(1);
        assertThat(result.trace()).anyMatch(step -> step.decision().equals("previous_violations_rechecked"));
        List<?> violations = (List<?>) result.metrics().get("criticViolations");
        assertThat(violations.contains("insufficient_review_confidence")).isEqualTo(!evidenceRecovers);
        assertThat(result.runtime().criticVerdict()).isEqualTo(evidenceRecovers ? "approved" : "vetoed");
    }
}
