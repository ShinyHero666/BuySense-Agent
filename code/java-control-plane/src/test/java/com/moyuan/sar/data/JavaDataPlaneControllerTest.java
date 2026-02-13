package com.moyuan.sar.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.buysense.BuySenseApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BuySenseApplication.class)
@AutoConfigureMockMvc
class JavaDataPlaneControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void exposesAllEightToolEndpoints() throws Exception {
        ObjectNode discovery = mapper.createObjectNode();
        discovery.put("domain_pack_id", "normal-3c-v1");
        discovery.put("query", "预算7000元，拍照手机搭配降噪耳机和充电器");
        discovery.putArray("requested_categories").add("phone").add("headphones").add("charger");
        discovery.putArray("use_cases").add("拍照").add("降噪");
        discovery.put("max_price", 7000);

        JsonNode search = postJson("/api/v2/discovery/search", discovery);
        JsonNode recommend = postJson("/api/v2/discovery/recommend", discovery);
        JsonNode ads = postJson("/api/v2/discovery/ads", discovery);
        assertThat(search.path("channel").asText()).isEqualTo("search");
        assertThat(recommend.path("channel").asText()).isEqualTo("recommendation");
        assertThat(ads.path("channel").asText()).isEqualTo("ads");

        String productId = search.path("items").get(0).path("product_id").asText();
        String skuId = search.path("items").get(0).path("sku_id").asText();
        String offerId = search.path("items").get(0).path("offer_id").asText();
        String accessorySku = recommend.path("items").findValuesAsText("sku_id").stream()
                .filter(value -> !value.equals(skuId)).findFirst().orElseThrow();

        ObjectNode reviewsRequest = mapper.createObjectNode();
        reviewsRequest.put("domain_pack_id", "normal-3c-v1");
        reviewsRequest.putArray("product_ids").add(productId);
        assertThat(postJson("/api/v2/evidence/reviews", reviewsRequest)
                .path("products").isArray()).isTrue();

        ObjectNode compatibilityRequest = mapper.createObjectNode();
        compatibilityRequest.put("domain_pack_id", "normal-3c-v1");
        compatibilityRequest.putArray("pairs").addObject()
                .put("product_sku_id", skuId)
                .put("accessory_sku_id", accessorySku);
        assertThat(postJson("/api/v2/evidence/compatibility", compatibilityRequest)
                .path("results").size()).isEqualTo(1);

        ObjectNode quoteRequest = mapper.createObjectNode();
        quoteRequest.put("domain_pack_id", "normal-3c-v1");
        quoteRequest.putArray("offer_ids").add(offerId);
        assertThat(postJson("/api/v2/pricing/quote", quoteRequest)
                .path("quotes").get(0).path("status").asText()).isEqualTo("active");

        ObjectNode fuseRequest = mapper.createObjectNode();
        fuseRequest.put("domain_pack_id", "normal-3c-v1");
        ArrayNode channels = fuseRequest.putArray("channels");
        channels.add(search);
        channels.add(recommend);
        channels.add(ads);
        fuseRequest.put("limit", 8);
        JsonNode fused = postJson("/api/v2/decision/fuse", fuseRequest);
        assertThat(fused.path("items").size()).isGreaterThan(0);

        ObjectNode bundleRequest = mapper.createObjectNode();
        bundleRequest.put("domain_pack_id", "normal-3c-v1");
        bundleRequest.set("items", fused.path("items"));
        bundleRequest.putArray("requested_categories").add("phone").add("headphones").add("charger");
        bundleRequest.put("intent", "bundle");
        bundleRequest.put("budget_max", 7000);
        bundleRequest.put("top_n", 3);
        assertThat(postJson("/api/v2/decision/bundles", bundleRequest)
                .path("optimizer_version").asText()).isEqualTo("constraint-enumeration-v3-budget-target");
    }

    @Test
    void toolEndpointsRejectUnknownFields() throws Exception {
        var response = mvc.perform(post("/api/v2/discovery/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"domain_pack_id":"normal-3c-v1","query":"手机","unexpected":true}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(body.path("error").asText()).isEqualTo("validation_error");
    }

    private JsonNode postJson(String path, JsonNode body) throws Exception {
        String content = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(content);
    }
}
