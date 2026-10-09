package com.signdesk.domain.bo;

import com.signdesk.engine.ResultRules;

import jakarta.validation.constraints.*;

public record RequestBo(
        @NotBlank @Size(max = 60) String name, boolean enabled, ResultRules rules, int version) {

    @Override
    public String toString() {
        return "RequestBo[redacted]";
    }
}
