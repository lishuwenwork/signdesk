package com.signdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.common.ApiException;
import com.signdesk.common.Json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ResultRulesInverseTest {
    ResultRules inverse(String path, Object value) {
        return ResultRules.validate(new ResultRules(new ResultRules.Match(path, value, null), null, null, null, true));
    }

    @Test
    void inverseFailureRequiresAnAvailableSameTypeScalar() {
        var rules = inverse("code", 0);
        for (String value : List.of("0", "0.0", "0e3"))
            assertEquals("success", rules.classify(200, "{\"code\":" + value + "}"));
        for (String value : List.of("1", "-1", "1.25", "99999"))
            assertEquals("failed", rules.classify(200, "{\"code\":" + value + "}"));
        for (String body : List.of("{}", "{\"code\":null}", "{\"code\":\"0\"}", "{\"code\":false}",
                "{\"code\":{}}", "{\"code\":[]}", "<html>error</html>", "", "null", "[]", "{\"code\":"))
            assertEquals("unknown", rules.classify(200, body), body);
        assertEquals("unknown", rules.classify(200, null));
    }

    @Test
    void inverseSupportsBooleansStringsAndNestedArrayPathsWithoutCoercion() {
        var bool = inverse("success", true);
        assertEquals("success", bool.classify(200, "{\"success\":true}"));
        assertEquals("failed", bool.classify(200, "{\"success\":false}"));
        assertEquals("unknown", bool.classify(200, "{\"success\":\"false\"}"));
        var text = inverse("code", "0");
        assertEquals("success", text.classify(200, "{\"code\":\"0\"}"));
        assertEquals("failed", text.classify(200, "{\"code\":\"ERROR\"}"));
        assertEquals("unknown", text.classify(200, "{\"code\":0}"));
        var nested = inverse("$.data.items[0].code", 0);
        assertEquals("success", nested.classify(200, "{\"data\":{\"items\":[{\"code\":0}]}}"));
        assertEquals("failed", nested.classify(200, "{\"data\":{\"items\":[{\"code\":5}]}}"));
        assertEquals("unknown", nested.classify(200, "{\"data\":{\"items\":[]}}"));
        assertEquals("unknown", nested.classify(200, "{\"data\":null}"));
    }

    @Test
    void completionOverridesGenericFailureButCredentialsAndExplicitErrorsRemainHigherPriority() {
        var rules = new ResultRules(new ResultRules.Match("success", true, null),
                new ResultRules.Match("message", "今日已签到", null),
                new ResultRules.Match("expired", true, null), new ResultRules.Match("success", false, null), true);
        String done = "{\"message\":\"今日已签到\",\"success\":false}";
        assertEquals("already_done", rules.classify(200, done));
        assertEquals("expired", rules.classify(200, "{\"message\":\"今日已签到\",\"success\":false,\"expired\":true}"));
        assertEquals("failed", rules.classify(200, "{\"success\":false}"));
        var explicit = new ResultRules(new ResultRules.Match("code", 0, null), null, null,
                new ResultRules.Match("message", "error", null), true);
        assertEquals("failed", explicit.classify(200, "{\"code\":0,\"message\":\"error\"}"));
        assertEquals("already_done", new ResultRules(rules.success(), rules.alreadyDone(), rules.expired(), rules.failed()).classify(200, done));
    }

    @Test
    void inverseAndCompletionNeverOverrideHttpErrors() {
        var rules = new ResultRules(new ResultRules.Match("code", 0, null),
                new ResultRules.Match("done", true, null), new ResultRules.Match("expired", true, null), null, true);
        assertEquals("expired", rules.classify(401, "{}"));
        assertEquals("failed", rules.classify(403, "{\"code\":0,\"done\":true}"));
        assertEquals("unknown", rules.classify(500, "{\"code\":5,\"done\":true}"));
        assertEquals("unknown", rules.classify(302, "{\"code\":0,\"done\":true}"));
        assertEquals("expired", rules.classify(500, "{\"code\":0,\"expired\":true}"));
    }

    @Test
    void inverseRejectsAmbiguousSuccessRulesWithoutRemovingExistingCapabilities() {
        var invalid = new ResultRules.Match[] {null, new ResultRules.Match("code", null, null),
                new ResultRules.Match(null, null, "success"), new ResultRules.Match("code", 0, "success"),
                new ResultRules.Match("bad[*]", 0, null), new ResultRules.Match("code", Map.of("nested", 0), null),
                new ResultRules.Match("code", List.of(0), null)};
        for (var success : invalid)
            assertThrows(ApiException.class, () -> ResultRules.validate(new ResultRules(success, null, null, null, true)));
        var nullValue = ResultRules.validate(new ResultRules(new ResultRules.Match("code", null, null), null, null, null));
        assertEquals("success", nullValue.classify(200, "{\"code\":null}"));
        var combined = ResultRules.validate(new ResultRules(new ResultRules.Match("code", 0, "success"), null, null, null));
        assertEquals("success", combined.classify(200, "{\"code\":0,\"message\":\"success\"}"));
        assertEquals("unknown", combined.classify(200, "{\"code\":0}"));
    }

    @Test
    void oldJsonDefaultsToOffAndFlagsSurviveRoundTrips() {
        var legacy = Json.read("{\"success\":{\"path\":\"code\",\"value\":0}}", ResultRules.class);
        assertEquals(ResultRules.defaults(), legacy);
        assertFalse(legacy.failureOnSuccessMismatch());
        assertEquals("unknown", legacy.classify(200, "{\"code\":99}"));
        assertEquals(legacy, Json.read(Json.write(legacy), ResultRules.class));
        for (Boolean flag : new Boolean[] {null, false, true}) {
            var rules = new ResultRules(new ResultRules.Match("code", 0, null), null, null, null, flag);
            var restored = Json.read(Json.write(rules), ResultRules.class);
            assertEquals(rules, restored);
            assertEquals(Boolean.TRUE.equals(flag) ? "failed" : "unknown", restored.classify(200, "{\"code\":99}"));
        }
        var explicitNull = Json.read("{\"success\":null,\"failureOnSuccessMismatch\":null}", ResultRules.class);
        assertNull(explicitNull.success());
        assertFalse(explicitNull.failureOnSuccessMismatch());
    }
}
