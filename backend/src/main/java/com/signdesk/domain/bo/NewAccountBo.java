package com.signdesk.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewAccountBo(@NotBlank @Size(max = 40) String alias, boolean enabled) {
    @Override
    public String toString() {
        return "NewAccountBo[redacted]";
    }
}
