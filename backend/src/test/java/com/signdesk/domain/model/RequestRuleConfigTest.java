package com.signdesk.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.common.Json;
import com.signdesk.engine.ResultRules;

import org.junit.jupiter.api.Test;

class RequestRuleConfigTest {
    @Test
    void unboundLegacyRulesKeepTheirStorageShapeAndBoundMetadataNeverBecomesAResultCategory() {
        String legacy = "{\"success\":{\"path\":\"code\",\"value\":0}}";
        var decoded = RequestRuleConfig.read(legacy);
        assertEquals(ResultRules.defaults(), decoded.rules());
        assertNull(decoded.templateId());
        assertFalse(Json.tree(decoded.toJson()).has("rules"));
        var bound = new RequestRuleConfig(decoded.rules(), "123");
        assertEquals(bound, RequestRuleConfig.read(bound.toJson()));
        assertEquals("123", Json.tree(bound.toJson()).path("templateId").asString());
        assertFalse(Json.write(bound.rules()).contains("templateId"));
        assertEquals("success", bound.rules().classify(200, "{\"code\":0}"));
        assertEquals(decoded, RequestRuleConfig.read(new RequestRuleConfig(bound.rules(), null).toJson()));
    }

    @Test
    void configurationDiagnosticsNeverRevealRuleValues() {
        var rules = new ResultRules(new ResultRules.Match("code", "config-fixture-secret", null), null, null, null);
        assertFalse(new RequestRuleConfig(rules, "123").toString().contains("config-fixture-secret"));
    }
}
