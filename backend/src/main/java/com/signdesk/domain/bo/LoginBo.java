package com.signdesk.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginBo(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 256) String password) {
    @Override
    public String toString() {
        return "LoginBo[redacted]";
    }
}
