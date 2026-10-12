package com.signdesk.engine;

import com.signdesk.common.ApiException;
import com.signdesk.common.Json;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

public record ResultRules(
        Match success, Match alreadyDone, Match expired, Match failed,
        Boolean failureOnSuccessMismatch) {
    public ResultRules {
        if (failureOnSuccessMismatch == null) failureOnSuccessMismatch = false;
    }

    public ResultRules(Match success, Match alreadyDone, Match expired, Match failed) {
        this(success, alreadyDone, expired, failed, false);
    }

    public record Match(String path, Object value, String contains) {
        @Override public String toString() { return "Match[redacted]"; }
    }

    private enum FieldMatch { EQUAL, DIFFERENT, UNAVAILABLE }

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
        if (rules.failureOnSuccessMismatch && !supportsFailureOnMismatch(rules.success))
            throw new ApiException("反向失败判断需要纯 JSON 字段成功规则，等于值为数字、字符串或布尔，不能附加文本包含");
        return rules;
    }

    private static boolean supportsFailureOnMismatch(Match rule) {
        return rule != null
                && rule.path() != null && !rule.path().isBlank()
                && (rule.contains() == null || rule.contains().isBlank())
                && (rule.value() instanceof Number
                        || rule.value() instanceof String
                        || rule.value() instanceof Boolean);
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
        // An explicit completion takes precedence over a generic operation failure.
        if (matches(alreadyDone, root, body)) return "already_done";
        if (matches(failed, root, body)) return "failed";
        if (matches(success, root, body)) return "success";
        if (failureOnSuccessMismatch && supportsFailureOnMismatch(success)
                && compareField(success, root) == FieldMatch.DIFFERENT) return "failed";
        return "unknown";
    }

    private boolean matches(Match rule, JsonNode root, String body) {
        if (rule == null) return false;
        if (rule.contains() != null
                && !rule.contains().isBlank()
                && (body == null || !body.contains(rule.contains()))) return false;
        if (rule.path() == null || rule.path().isBlank())
            return rule.contains() != null && !rule.contains().isBlank();
        return compareField(rule, root) == FieldMatch.EQUAL;
    }

    private FieldMatch compareField(Match rule, JsonNode root) {
        if (root == null) return FieldMatch.UNAVAILABLE;
        String pointer = rule.path().replaceFirst("^\\$\\.", "").replaceAll("\\[(\\d+)\\]", ".$1");
        JsonNode node = root;
        for (String part : pointer.split("\\.")) {
            if (node.isArray()) {
                try {
                    node = node.path(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    return FieldMatch.UNAVAILABLE;
                }
            } else node = node.path(part);
        }
        if (node.isMissingNode()) return FieldMatch.UNAVAILABLE;
        Object expected = rule.value();
        if (expected == null) return node.isNull() ? FieldMatch.EQUAL : FieldMatch.UNAVAILABLE;
        boolean equal;
        if (expected instanceof Number) {
            if (!node.isNumber()) return FieldMatch.UNAVAILABLE;
            equal = new BigDecimal(node.asText()).compareTo(new BigDecimal(expected.toString())) == 0;
        } else if (expected instanceof Boolean) {
            if (!node.isBoolean()) return FieldMatch.UNAVAILABLE;
            equal = node.asBoolean() == (boolean) expected;
        } else {
            if (!node.isString()) return FieldMatch.UNAVAILABLE;
            equal = node.asText().equals(expected.toString());
        }
        return equal ? FieldMatch.EQUAL : FieldMatch.DIFFERENT;
    }
}
