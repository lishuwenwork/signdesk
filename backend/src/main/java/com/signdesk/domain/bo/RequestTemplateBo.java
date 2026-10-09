package com.signdesk.domain.bo;

import com.signdesk.engine.ResultRules;

import jakarta.validation.constraints.*;

public record RequestTemplateBo(
        @NotBlank @Size(max = 60) String name, ResultRules rules, @Min(1) int version) {

    @Override
    public String toString() {
        return "RequestTemplateBo[redacted]";
    }
}
