package com.signdesk.domain.bo;

import com.signdesk.engine.ResultRules;

import jakarta.validation.constraints.*;

public record NewRequestBo(
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Size(max = 262144) String curl,
        boolean enabled,
        ResultRules rules,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String templateId) {

    public NewRequestBo(String name, String curl, boolean enabled, ResultRules rules) {
        this(name, curl, enabled, rules, null);
    }

    @Override
    public String toString() {
        return "NewRequestBo[redacted]";
    }
}
