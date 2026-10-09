package com.signdesk.engine;

import com.signdesk.common.ApiException;
import com.signdesk.common.Json;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

public record ResultRules(Match success, Match alreadyDone, Match expired, Match failed) {
    public record Match(String path, Object value, String contains) {
        @Override public String toString() { return "Match[redacted]"; }
    }

    @Override public String toString() { return "ResultRules[redacted]"; }

    public static ResultRules defaults() {
        return new ResultRules(new Match("code", 0, null), null, null, null);
    }

    public static ResultRules validate(ResultRules rules) {
        if (rules == null) rules = defaults();
        for (Match m :
                new Match[] {rules.success, rules.alreadyDone, rules.expired, rules.failed}) {
            if (m == null) continue;
            if (m.path() != null
                    && !m.path().isBlank()
                    && !m.path()
                            .matches(
                                    "(?:\\$\\.)?[A-Za-z0-9_-]+(?:\\[\\d+\\])?(?:\\.[A-Za-z0-9_-]+(?:\\[\\d+\\])?)*"))
                throw new ApiException("字段路径仅支持 code、data.code、data.items[0].code 等格式");
            if ((m.path() == null || m.path().isBlank())
                    && (m.contains() == null || m.contains().isBlank()))
                throw new ApiException("结果规则需要字段路径或文本包含");
            if (m.value() instanceof java.util.Map || m.value() instanceof java.util.List)
                throw new ApiException("规则等于值需为字符串、数字、布尔或 null");
            if (m.contains() != null && m.contains().length() > 200)
                throw new ApiException("文本规则过长");
        }
        return rules;
    }

    public String classify(int httpStatus, String body) {
        if (httpStatus == 401) return "expired";
        JsonNode root = null;
        try {
            root = Json.tree(body);
        } catch (Exception ignored) {
        }
        // Never turn an HTTP error into a success based on an accidental matching body.
        if (httpStatus < 200 || httpStatus >= 300) {
            if (matches(expired, root, body)) return "expired";
            return httpStatus >= 400 && httpStatus < 500 ? "failed" : "unknown";
        }
        if (matches(expired, root, body)) return "expired";
        if (matches(failed, root, body)) return "failed";
        if (matches(alreadyDone, root, body)) return "already_done";
        if (matches(success, root, body)) return "success";
        return "unknown";
    }

    private boolean matches(Match rule, JsonNode root, String body) {
        if (rule == null) return false;
        if (rule.contains() != null
                && !rule.contains().isBlank()
                && !body.contains(rule.contains())) return false;
        if (rule.path() == null || rule.path().isBlank())
            return rule.contains() != null && !rule.contains().isBlank();
        if (root == null) return false;
        String pointer = rule.path().replaceFirst("^\\$\\.", "").replaceAll("\\[(\\d+)\\]", ".$1");
        JsonNode node = root;
        for (String part : pointer.split("\\.")) {
            if (node.isArray()) {
                try {
                    node = node.path(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else node = node.path(part);
        }
        if (node.isMissingNode()) return false;
        Object expected = rule.value();
        if (expected == null) return node.isNull();
        if (expected instanceof Number)
            return node.isNumber()
                    && new BigDecimal(node.asText()).compareTo(new BigDecimal(expected.toString()))
                            == 0;
        if (expected instanceof Boolean)
            return node.isBoolean() && node.asBoolean() == (boolean) expected;
        return node.isString() && node.asText().equals(expected.toString());
    }
}
