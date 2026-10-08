package com.signdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResultRulesTest {
    @Test
    void distinguishesJsonTypesAndHttpErrors() {
        var rules = ResultRules.defaults();
        assertEquals("success", rules.classify(200, "{\"code\":0}"));
        assertEquals("unknown", rules.classify(200, "{\"code\":\"0\"}"));
        assertEquals("unknown", rules.classify(200, "{\"code\":42}"));
        assertEquals("expired", rules.classify(401, "{\"code\":0}"));
        assertEquals("failed", rules.classify(400, "{\"code\":0}"));
        assertEquals("unknown", rules.classify(500, "{\"code\":0}"));
    }

    @Test
    void supportsNestedAndAlreadyDone() {
        var rules =
                ResultRules.validate(
                        new ResultRules(
                                new ResultRules.Match("data.items[0].code", true, null),
                                new ResultRules.Match("data.code", 1001, null),
                                new ResultRules.Match("message", null, "未登录"),
                                null));
        assertEquals("success", rules.classify(200, "{\"data\":{\"items\":[{\"code\":true}]}}"));
        assertEquals("already_done", rules.classify(200, "{\"data\":{\"code\":1001}}"));
    }
}
