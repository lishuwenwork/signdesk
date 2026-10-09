package com.signdesk.service.impl;

import com.signdesk.common.*;
import com.signdesk.domain.AppSettings;
import com.signdesk.domain.bo.SettingsBo;
import com.signdesk.domain.vo.SettingsVo;
import com.signdesk.engine.ProxySettings;
import com.signdesk.mapper.AppSettingsMapper;
import com.signdesk.service.ISettingsService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements ISettingsService {
    private final AppSettingsMapper settings;

    @Override
    public SettingsVo query() {
        var r = Checks.found(settings.selectById(1));
        return new SettingsVo(
                Checks.flag(r.getPaused()),
                r.getConcurrency(),
                r.getTimeoutSeconds(),
                r.getRetentionDays(),
                r.getVersion(),
                new ProxySettings(r.getProxyMode(), r.getProxyHost(), r.getProxyPort()));
    }

    @Override
    @Transactional
    public void save(SettingsBo b) {
        var proxy = b.proxy() == null ? ProxySettings.system() : b.proxy().validated();
        validateRanges(b.concurrency(), b.timeoutSeconds(), b.retentionDays());
        var r = new AppSettings();
        r.setId(1);
        r.setPaused(b.paused() ? 1 : 0);
        r.setConcurrency(b.concurrency());
        r.setTimeoutSeconds(b.timeoutSeconds());
        r.setRetentionDays(b.retentionDays());
        r.setVersion(b.version());
        r.setProxyMode(proxy.mode());
        r.setProxyHost(proxy.host());
        r.setProxyPort(proxy.port());
        Checks.changed(settings.updateById(r));
    }

    public static void validateRanges(int concurrency, int timeout, int retention) {
        if (concurrency < 1
                || concurrency > 8
                || timeout < 1
                || timeout > 120
                || retention < 1
                || retention > 365) throw new ApiException("并发 1～8、超时 1～120 秒、日志保留 1～365 天");
    }
}
