package com.signdesk.domain.bo;

import com.signdesk.engine.ResultRules;

import jakarta.validation.constraints.*;

public record RuleTestBo(ResultRules rules, int httpStatus, @Size(max = 1048576) String body) {

    @Override
    public String toString() {
        return "RuleTestBo[redacted]";
    }
}
