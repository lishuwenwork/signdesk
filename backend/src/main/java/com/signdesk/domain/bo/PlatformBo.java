package com.signdesk.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

/** Platform input only; version is the edit version, not a request or schedule revision. */
@Getter
@Setter
public class PlatformBo {
    @NotBlank
    @Size(max = 40)
    private String name;
    @Size(max = 500)
    private String note;
    private boolean enabled;
    private int version;
}
