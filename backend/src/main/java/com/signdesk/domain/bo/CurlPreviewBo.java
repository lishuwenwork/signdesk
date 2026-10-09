package com.signdesk.domain.bo;

import jakarta.validation.constraints.*;

public record CurlPreviewBo(@NotBlank @Size(max = 262144) String curl) {

    @Override
    public String toString() {
        return "CurlPreviewBo[redacted]";
    }
}
