package com.signdesk.domain.vo;

import com.signdesk.engine.ResultRules;

import java.util.List;

public record TemplateApplyPreviewVo(
        String templateId, String templateName, int templateVersion, String mode,
        ResultRules rules, List<Target> requests) {
    public record Target(
            String id, String name, String accountAlias, int version, boolean enabled,
            String templateId, String templateName) {
        @Override public String toString() { return "Target[redacted]"; }
    }

    @Override public String toString() { return "TemplateApplyPreviewVo[redacted]"; }
}
