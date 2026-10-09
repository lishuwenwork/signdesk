package com.signdesk.domain.vo;

import com.signdesk.engine.ProxySettings;

public record SettingsVo(
        boolean paused,
        int concurrency,
        int timeoutSeconds,
        int retentionDays,
        int version,
        ProxySettings proxy) {

    @Override
    public String toString() {
        return "SettingsVo[redacted]";
    }
}
