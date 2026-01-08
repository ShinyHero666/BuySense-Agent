package com.buysense.agent;

import com.buysense.platform.DomainPackRegistry;
import com.buysense.retail.ReviewEvidenceGateway;
import com.buysense.sar.data.JavaRetailDataPlane;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SearchAdsRecsLeadServiceDegradationParityTest {
    @Autowired
    private IntentParser parser;
    @Autowired
    private DomainPackRegistry domains;
    @Autowired
    private ReviewEvidenceGateway reviewEvidence;
    @Autowired
    private ModelPortAgentBridge model;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    @Qualifier("collaborationExecutor")
    private Executor collaborationExecutor;

    @Test
    void adsRetrievalFailureStillRunsTheAdsRoleAgentAndPublishesItsArtifact() {
        JavaRetailDataPlane brokenAds = new JavaRetailDataPlane(mapper) {
            @Override
            public Map<String, Object> discovery(
                    String packId,
                    String channel,
                    JsonNode payload
            ) {
                if ("ads".equals(channel)) {
                    throw new IllegalStateException("simulated ads outage");
                }
                return super.discovery(packId, channel, payload);
            }
        };
        SearchAdsRecsLeadService service = new SearchAdsRecsLeadService(
                parser,
                domains,
                brokenAds,
                reviewEvidence,
                model,
                mapper,
                collaborationExecutor);

        SearchAdsRecsLeadService.Execution execution = service.decide(
                "degraded-ads-agent-loop",
                "iphone 6500 budget",
                DomainPackRegistry.DEFAULT_PACK_ID,
                new SearchAdsRecsLeadService.DiscoveryContext(
                        "degraded-user",
                        "degraded-session",
                        false,
                        List.of(),
                        List.of(),
                        List.of()));

        assertThat(execution.result().trace()).anySatisfy(step -> {
            assertThat(step.stage()).isEqualTo("ads");
            assertThat(step.decision()).isEqualTo("degraded");
        });
        assertThat(execution.result().trace()).anySatisfy(step -> {
            assertThat(step.stage()).isEqualTo("ads");
            assertThat(step.decision()).isEqualTo("model_execution");
        });
        AgentArtifact adsArtifact = execution.artifacts().stream()
                .filter(item -> item.producer().equals("ads"))
                .filter(item -> item.type().equals("candidate_set"))
                .findFirst()
                .orElseThrow();
        assertThat((List<?>) adsArtifact.payload().get("candidates")).isEmpty();
        assertThat(execution.tasks()).anySatisfy(task -> {
            assertThat(task.role()).isEqualTo("ads");
            assertThat(task.status()).isEqualTo("completed");
        });
    }
}
