package com.signdesk.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record TemplateApplyBo(
        @NotBlank @Pattern(regexp = "selected|linked") String mode,
        @Min(1) int templateVersion,
        @NotEmpty @Size(max = 10000) List<@NotNull @Valid Target> targets) {
    public record Target(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String id, @Min(1) int version) {
        @Override public String toString() { return "Target[redacted]"; }
    }

    @Override public String toString() { return "TemplateApplyBo[redacted]"; }
}
