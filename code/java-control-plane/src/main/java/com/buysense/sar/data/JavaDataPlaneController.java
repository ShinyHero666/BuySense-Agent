package com.buysense.sar.data;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** HTTP facade for the eight tool endpoints exposed by the BuySense data plane. */
@RestController
public final class JavaDataPlaneController {
    private final JavaRetailDataPlane dataPlane;

    public JavaDataPlaneController(JavaRetailDataPlane dataPlane) {
        this.dataPlane = dataPlane;
    }

    @PostMapping(path = "/api/v2/discovery/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> search(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/discovery/search", payload);
    }

    @PostMapping(path = "/api/v2/discovery/recommend", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> recommend(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/discovery/recommend", payload);
    }

    @PostMapping(path = "/api/v2/discovery/ads", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ads(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/discovery/ads", payload);
    }

    @PostMapping(path = "/api/v2/evidence/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reviews(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/evidence/reviews", payload);
    }

    @PostMapping(path = "/api/v2/evidence/compatibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> compatibility(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/evidence/compatibility", payload);
    }

    @PostMapping(path = "/api/v2/pricing/quote", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> quote(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/pricing/quote", payload);
    }

    @PostMapping(path = "/api/v2/decision/fuse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> fuse(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/decision/fuse", payload);
    }

    @PostMapping(path = "/api/v2/decision/bundles", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> bundles(@RequestBody JsonNode payload) {
        return dataPlane.dispatch("/api/v2/decision/bundles", payload);
    }
}
