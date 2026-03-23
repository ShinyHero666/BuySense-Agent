package com.moyuan.buysense.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.run.RunEvent;
import com.moyuan.buysense.run.RunService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RunSecurityContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RunService runs;

    @Test
    void isolatesRunReadCancelEventsAndConfirmationAcrossAnonymousSessions() throws Exception {
        Creation ownerProposal = create(null, "security-owner-proposal", Map.of(
                "message", "预算7000元，搭配一套拍照手机、降噪耳机和充电器",
                "domainPackId", "normal-3c-v1"));
        JsonNode proposal = awaitTerminal(ownerProposal.runId(), ownerProposal.cookie());
        assertThat(proposal.at("/result/phase").asText()).isEqualTo("proposal");

        Creation otherSessionSeed = create(null, "security-other-seed", Map.of(
                "message", "预算3000元，不要广告，推荐拍照手机"));
        awaitTerminal(otherSessionSeed.runId(), otherSessionSeed.cookie());
        Cookie otherCookie = otherSessionSeed.cookie();

        mvc.perform(get("/api/v2/runs/" + ownerProposal.runId()).cookie(otherCookie))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v2/runs/" + ownerProposal.runId() + "/cancel").cookie(otherCookie))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/runs/" + ownerProposal.runId() + "/events")
                        .cookie(otherCookie)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());

        JsonNode crossSessionError = responseJson(mvc.perform(post("/api/v2/runs")
                        .cookie(otherCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "security-cross-confirm")
                        .content(mapper.writeValueAsString(Map.of(
                                "message", "确认生成购物车草案",
                                "confirmed", true,
                                "domainPackId", "normal-3c-v1",
                                "proposalRunId", ownerProposal.runId()))))
                .andExpect(status().isUnprocessableEntity()));
        assertThat(crossSessionError.path("error").asText()).isEqualTo("proposal_run_not_found");
    }

    @Test
    void confirmationBindsToTheExactQualifiedProposalAndItsAmount() throws Exception {
        Creation creation = create(null, "binding-proposal", Map.of(
                "message", "总预算7000元，选拍照手机并搭配降噪耳机和充电器",
                "domainPackId", "normal-3c-v1"));
        JsonNode proposal = awaitTerminal(creation.runId(), creation.cookie());
        var expectedTotal = proposal.at("/result/decision/bundle/totalPrice").decimalValue();

        Creation confirmation = create(creation.cookie(), "binding-confirmation", Map.of(
                "message", "确认生成购物车草案",
                "confirmed", true,
                "domainPackId", "normal-3c-v1",
                "proposalRunId", creation.runId()));
        JsonNode cart = awaitTerminal(confirmation.runId(), creation.cookie());

        assertThat(cart.path("proposalRunId").asText()).isEqualTo(creation.runId());
        assertThat(cart.at("/result/phase").asText()).isEqualTo("cart_draft");
        assertThat(cart.at("/result/cartDraft/totalPrice").decimalValue())
                .isEqualByComparingTo(expectedTotal);
        assertThat(cart.at("/result/cartDraft/paymentAuthorized").asBoolean()).isFalse();
        assertThat(runs.require(confirmation.runId()).getEvents())
                .extracting(RunEvent::eventType)
                .contains("policy_gate", "result")
                .doesNotContain("model_execution");

        JsonNode mismatch = responseJson(mvc.perform(post("/api/v2/runs")
                        .cookie(creation.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "binding-mismatch")
                        .content(mapper.writeValueAsString(Map.of(
                                "message", "确认生成购物车草案",
                                "confirmed", true,
                                "domainPackId", "outdoor-camping-v1",
                                "proposalRunId", creation.runId()))))
                .andExpect(status().isUnprocessableEntity()));
        assertThat(mismatch.path("error").asText()).isEqualTo("proposal_extension_mismatch");
    }

    @Test
    void preventsIdempotencyKeyReuseAcrossDifferentDomainRequests() throws Exception {
        Creation first = create(null, "domain-bound-idempotency", Map.of(
                "message", "预算900元，推荐一套产品",
                "domainPackId", "normal-3c-v1"));

        JsonNode conflict = responseJson(mvc.perform(post("/api/v2/runs")
                        .cookie(first.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "domain-bound-idempotency")
                        .content(mapper.writeValueAsString(Map.of(
                                "message", "预算900元，推荐一套产品",
                                "domainPackId", "outdoor-camping-v1"))))
                .andExpect(status().isConflict()));

        assertThat(conflict.path("error").asText()).isEqualTo("idempotency_key_reused");
        awaitTerminal(first.runId(), first.cookie());
    }

    @Test
    void servesOutdoorDomainThroughTheHttpContractAndVetoesInfeasiblePlans() throws Exception {
        JsonNode registry = responseJson(mvc.perform(get("/api/v2/domain-packs"))
                .andExpect(status().isOk()));
        assertThat(registry.path("defaultPackId").asText()).isEqualTo("normal-3c-v1");
        assertThat(registry.path("packs").size()).isEqualTo(2);

        Creation outdoor = create(null, "outdoor-http-contract", Map.of(
                "message", "预算900元，搭配一套高海拔防风炉具、气罐和锅具，不要广告",
                "domainPackId", "outdoor-camping-v1"));
        JsonNode completed = awaitTerminal(outdoor.runId(), outdoor.cookie());
        assertThat(completed.path("domainPackId").asText()).isEqualTo("outdoor-camping-v1");
        assertThat(completed.at("/result/phase").asText()).isEqualTo("proposal");
        assertThat(completed.at("/result/decision/bundle/items").size()).isEqualTo(3);
        assertThat(completed.at("/result/decision/bundle/withinBudget").asBoolean()).isTrue();
        assertThat(completed.at("/result/decision/critique/verdict").asText()).isEqualTo("approved");

        Creation infeasible = create(outdoor.cookie(), "infeasible-http-contract", Map.of(
                "message", "预算1元，推荐手机",
                "domainPackId", "normal-3c-v1"));
        JsonNode vetoed = awaitTerminal(infeasible.runId(), outdoor.cookie());
        assertThat(vetoed.at("/result/phase").asText()).isEqualTo("needs_replan");
        assertThat(vetoed.at("/result/decision/critique/verdict").asText()).isEqualTo("vetoed");
        assertThat(runs.require(infeasible.runId()).getEvents().stream()
                .filter(event -> event.eventType().equals("policy_gate"))
                .toList()).anySatisfy(event ->
                        assertThat(event.payload()).containsEntry("approved", false));
    }

    private Creation create(Cookie cookie, String idempotencyKey, Map<String, Object> body) throws Exception {
        var request = post("/api/v2/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(mapper.writeValueAsString(body));
        if (cookie != null) request.cookie(cookie);
        var response = mvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        Cookie resolved = cookie == null ? response.getCookie(SessionIdentity.COOKIE) : cookie;
        assertThat(resolved).isNotNull();
        return new Creation(mapper.readTree(response.getContentAsString()).path("runId").asText(), resolved);
    }

    private JsonNode awaitTerminal(String runId, Cookie cookie) throws Exception {
        JsonNode value = null;
        for (int attempt = 0; attempt < 150; attempt++) {
            value = responseJson(mvc.perform(get("/api/v2/runs/" + runId).cookie(cookie))
                    .andExpect(status().isOk()));
            String status = value.path("status").asText();
            if (status.equals("completed") || status.equals("failed") || status.equals("cancelled")) {
                return value;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run did not become terminal: " + value);
    }

    private JsonNode responseJson(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return mapper.readTree(action.andReturn().getResponse().getContentAsString());
    }

    private record Creation(String runId, Cookie cookie) {
    }
}