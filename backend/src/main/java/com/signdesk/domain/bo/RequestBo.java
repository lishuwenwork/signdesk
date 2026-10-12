package com.signdesk.domain.bo;

import com.signdesk.engine.ResultRules;

import jakarta.validation.constraints.*;

public record RequestBo(
        @NotBlank @Size(max = 60) String name, boolean enabled, ResultRules rules, int version,
        Boolean unlinkTemplate) {

    public RequestBo(String name, boolean enabled, ResultRules rules, int version) {
        this(name, enabled, rules, version, false);
    }

    @Override
    public String toString() {
        return "RequestBo[redacted]";
    }
}
