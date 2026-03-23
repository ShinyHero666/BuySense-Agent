package com.moyuan.buysense.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.run.RunEvent;
import com.moyuan.buysense.run.RunService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RunService runs;

    @Test
    void idempotencyKeyReplaysTheSameRun() throws Exception {
        String body = "{\"message\":\"预算7000元，配一套拍照手机和降噪耳机\"}";
        var firstResponse = mvc.perform(post("/api/v2/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "turn-1")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        Cookie cookie = firstResponse.getCookie(SessionIdentity.COOKIE);
        String first = firstResponse.getContentAsString();

        String replay = mvc.perform(post("/api/v2/runs")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "turn-1")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode firstJson = objectMapper.readTree(first);
        JsonNode replayJson = objectMapper.readTree(replay);
        assertThat(firstJson.path("runId").asText()).isNotBlank();
        assertThat(cookie).isNotNull();
        assertThat(replayJson.path("runId").asText()).isEqualTo(firstJson.path("runId").asText());
        assertThat(replayJson.path("idempotentReplay").asBoolean()).isTrue();
        awaitTerminalRun(firstJson.path("runId").asText(), cookie);
    }

    @Test
    void incompleteRequestStopsBeforeRetrievalAndReturnsClarification() throws Exception {
        var creation = mvc.perform(post("/api/v2/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "clarification-contract")
                        .content("""
                                {"message":"预算5000元，适合我就行","confirmed":false}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        Cookie cookie = creation.getCookie(SessionIdentity.COOKIE);
        String runId = objectMapper.readTree(creation.getContentAsString())
                .path("runId").asText();

        JsonNode completed = awaitTerminalRun(runId, cookie);
        assertThat(completed.at("/result/phase").asText()).isEqualTo("clarification");
        assertThat(completed.at("/result/message").asText()).isNotBlank();
        assertThat(completed.at("/result/decision/slate").size()).isZero();
        assertThat(completed.at("/result/decision/runtime/mode").asText()).isEqualTo("hybrid");
        assertThat(runs.require(runId).getEvents().stream()
                .noneMatch(event -> "data_plane_result".equals(event.payload().get("event"))))
                .isTrue();
        assertThat(runs.require(runId).getEvents().stream()
                .anyMatch(event -> "clarification_question".equals(
                        event.payload().get("artifactType"))))
                .isTrue();
    }

    @Test
    void servesTheReactContractFromRunCreationThroughQualityGates() throws Exception {
        String body = """
                {"message":"总预算7000元，重视拍照和续航，选手机并搭配降噪耳机和充电器",
                 "confirmed":false}
                """;
        var creation = mvc.perform(post("/api/v2/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "frontend-contract")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        Cookie cookie = creation.getCookie(SessionIdentity.COOKIE);
        String runId = objectMapper.readTree(creation.getContentAsString()).path("runId").asText();

        JsonNode completed = awaitTerminalRun(runId, cookie);
        assertThat(completed.path("status").asText()).isEqualTo("completed");
        assertThat(completed.at("/result/phase").asText()).isEqualTo("proposal");
        assertThat(completed.at("/result/decision/plan/intent").asText())
                .isEqualTo("bundle_recommendation");
        assertThat(completed.at("/result/decision/plan/requirements/budgetMax").decimalValue())
                .isEqualByComparingTo("7000");
        assertThat(completed.at("/result/decision/slate").isArray()).isTrue();
        assertThat(completed.at("/result/decision/slate").size()).isGreaterThan(0);
        assertThat(completed.at("/result/decision/bundle/items").size()).isEqualTo(3);
        assertThat(completed.at("/result/decision/bundle/withinBudget").asBoolean()).isTrue();
        assertThat(completed.at("/result/decision/critique/verdict").asText()).isEqualTo("approved");
        assertThat(completed.at("/result/decision/runtime/mode").asText()).isEqualTo("hybrid");

        assertThat(runs.require(runId).getEvents().stream().map(RunEvent::eventType).toList())
                .contains("run_created", "run_started", "task", "artifact", "policy_gate", "result");
        assertThat(runs.require(runId).getEvents())
                .allMatch(event -> event.schemaVersion().equals("2.0"));
        int persistedEventCount = runs.require(runId).getEvents().size();
        var proposalTotal = completed.at("/result/decision/bundle/totalPrice").decimalValue();

        String cartCreationBody = objectMapper.writeValueAsString(Map.of(
                "message", "确认生成购物车草案",
                "confirmed", true,
                "domainPackId", "normal-3c-v1",
                "proposalRunId", runId));
        JsonNode cartCreation = objectMapper.readTree(mvc.perform(post("/api/v2/runs")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "cart-contract")
                        .content(cartCreationBody))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        String cartRunId = cartCreation.path("runId").asText();
        JsonNode cartRun = awaitTerminalRun(cartRunId, cookie);
        assertThat(cartRun.at("/result/phase").asText()).isEqualTo("cart_draft");
        assertThat(cartRun.at("/result/cartDraft/draftId").asText()).isNotBlank();
        assertThat(cartRun.at("/result/cartDraft/totalPrice").decimalValue())
                .isEqualByComparingTo(proposalTotal);
        assertThat(cartRun.at("/result/cartDraft/paymentAuthorized").asBoolean()).isFalse();
        assertThat(runs.require(cartRunId).getEvents().stream()
                .map(RunEvent::eventType).toList())
                .contains("artifact", "policy_gate", "result")
                .doesNotContain("model_execution");

        mvc.perform(put("/api/v2/preferences")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"personalizationEnabled":false,"preferredBrand":"Apple"}
                                """))
                .andExpect(status().isOk());

        runs.reloadFromStorage();
        assertThat(runs.require(runId).getResult()).isNotNull();
        assertThat(runs.require(runId).getEvents()).hasSize(persistedEventCount);
        assertThat(runs.require(runId).getEvents().stream().map(RunEvent::eventType).toList())
                .contains("result");
        assertThat(runs.require(cartRunId).getCartDraft()).isNotNull();
        assertThat(runs.require(cartRunId).getCartDraft().paymentAuthorized()).isFalse();

        JsonNode replay = objectMapper.readTree(mvc.perform(post("/api/v2/runs")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "frontend-contract")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(replay.path("runId").asText()).isEqualTo(runId);
        assertThat(replay.path("idempotentReplay").asBoolean()).isTrue();
        JsonNode preference = getJson("/api/v2/preferences", cookie);
        assertThat(preference.path("personalizationEnabled").asBoolean()).isFalse();
        assertThat(preference.path("preferredBrand").asText()).isEqualTo("Apple");

        JsonNode metrics = getJson("/metrics", cookie);
        assertThat(metrics.at("/northStar/denominator").asLong()).isGreaterThan(0);
        assertThat(metrics.at("/layers/constraint/qualifiedDecisionRate").isNumber()).isTrue();

        JsonNode health = getJson("/health", cookie);
        assertThat(health.at("/dataPlane/status").asText()).isEqualTo("embedded");
        assertThat(health.path("paymentEnabled").asBoolean()).isFalse();

        JsonNode quality = getJson("/api/v2/quality", cookie);
        assertThat(quality.path("evaluation_kind").asText())
                .isEqualTo("human_authored_business_cases");
        assertThat(quality.path("case_count").asInt()).isEqualTo(40);
        assertThat(quality.path("passed").asBoolean()).isTrue();

        String index = mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(index).contains("<div id=", "root", "</div>");
    }

    private JsonNode awaitTerminalRun(String runId, Cookie cookie) throws Exception {
        JsonNode value = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            value = getJson("/api/v2/runs/" + runId, cookie);
            String runStatus = value.path("status").asText();
            if (runStatus.equals("completed") || runStatus.equals("failed") || runStatus.equals("cancelled")) {
                return value;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run did not become terminal: " + value);
    }

    private JsonNode getJson(String path, Cookie cookie) throws Exception {
        String content = mvc.perform(get(path).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(content);
    }
}
