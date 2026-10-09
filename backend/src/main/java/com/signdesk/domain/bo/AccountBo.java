package com.signdesk.domain.bo;

import jakarta.validation.constraints.*;

public record AccountBo(@NotBlank @Size(max = 40) String alias, boolean enabled, int version) {

    @Override
    public String toString() {
        return "AccountBo[redacted]";
    }
}
