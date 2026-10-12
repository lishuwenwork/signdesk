package com.signdesk.domain.model;

import com.signdesk.common.Json;
import com.signdesk.engine.ResultRules;

import java.util.Objects;

/** Request-only metadata uses the existing JSON column; execution snapshots contain rules alone. */
public record RequestRuleConfig(ResultRules rules, String templateId) {
    public RequestRuleConfig {
        Objects.requireNonNull(rules);
    }

    public static RequestRuleConfig read(String json) {
        var root = Json.tree(json);
        return root.has("rules")
                ? Json.read(json, RequestRuleConfig.class)
                : new RequestRuleConfig(Json.read(json, ResultRules.class), null);
    }

    public String toJson() {
        return templateId == null ? Json.write(rules) : Json.write(this);
    }

    @Override public String toString() { return "RequestRuleConfig[redacted]"; }
}
