package com.signdesk.domain.vo;

public record BackupPreviewVo(
        int platforms,
        int accounts,
        int requests,
        int templates,
        boolean includesRequests,
        String mode,
        String message) {}
