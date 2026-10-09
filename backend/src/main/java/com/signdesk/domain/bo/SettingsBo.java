package com.signdesk.domain.bo;

import com.signdesk.engine.ProxySettings;

public record SettingsBo(
        boolean paused,
        int concurrency,
        int timeoutSeconds,
        int retentionDays,
        int version,
        ProxySettings proxy) {

    @Override
    public String toString() {
        return "SettingsBo[redacted]";
    }
}
