package com.signdesk.domain.bo;

import jakarta.validation.constraints.*;

public record CurlBo(@NotBlank @Size(max = 262144) String curl, @Min(1) int version) {

    @Override
    public String toString() {
        return "CurlBo[redacted]";
    }
}
