package com.signdesk.domain.bo;

import com.signdesk.engine.ResultRules;

import jakarta.validation.constraints.*;

public record NewRequestBo(
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Size(max = 262144) String curl,
        boolean enabled,
        ResultRules rules) {

    @Override
    public String toString() {
        return "NewRequestBo[redacted]";
    }
}
