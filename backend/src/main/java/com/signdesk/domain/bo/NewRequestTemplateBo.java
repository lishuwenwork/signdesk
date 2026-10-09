package com.signdesk.domain.bo;

import com.signdesk.engine.ResultRules;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewRequestTemplateBo(@NotBlank @Size(max = 60) String name, ResultRules rules) {
    @Override
    public String toString() {
        return "NewRequestTemplateBo[redacted]";
    }
}
