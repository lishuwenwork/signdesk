package com.signdesk.run;

import com.signdesk.common.ApiException;
import com.signdesk.engine.ProxySettings;
import com.signdesk.platform.CatalogService;
import com.signdesk.storage.Db;

import org.springframework.stereotype.Service;

@Service
public class SettingsService {
    public record Settings(
            boolean paused,
            int concurrency,
            int timeoutSeconds,
            int retentionDays,
            int version,
            ProxySettings proxy) {
        public Settings {
            proxy = proxy == null ? ProxySettings.system() : proxy;
        }

        public Settings(
                boolean paused, int concurrency, int timeoutSeconds, int retentionDays, int version) {
            this(paused, concurrency, timeoutSeconds, retentionDays, version, ProxySettings.system());
        }
    }

    private final Db db;

    public SettingsService(Db db) {
        this.db = db;
    }

    public Settings get() {
        var r = db.one("SELECT * FROM settings WHERE id=1");
        return new Settings(
                Db.flag(r, "paused"),
                Db.integer(r, "concurrency"),
                Db.integer(r, "timeoutSeconds"),
                Db.integer(r, "retentionDays"),
                Db.integer(r, "version"),
                new ProxySettings(
                        Db.text(r, "proxyMode"), Db.text(r, "proxyHost"), Db.integer(r, "proxyPort")));
    }

    public void save(Settings settings) {
        settings.proxy().validated();
        if (settings.concurrency() < 1
                || settings.concurrency() > 8
                || settings.timeoutSeconds() < 1
                || settings.timeoutSeconds() > 120
                || settings.retentionDays() < 1
                || settings.retentionDays() > 365)
            throw new ApiException("并发 1～8、超时 1～120 秒、日志保留 1～365 天");
        if (db.update(
                        "UPDATE settings SET"
                            + " paused=?,concurrency=?,timeout_seconds=?,retention_days=?,"
                            + " proxy_mode=?,proxy_host=?,proxy_port=?,version=version+1"
                            + " WHERE id=1 AND version=?",
                        settings.paused() ? 1 : 0,
                        settings.concurrency(),
                        settings.timeoutSeconds(),
                        settings.retentionDays(),
                        settings.proxy().mode(),
                        settings.proxy().host(),
                        settings.proxy().port(),
                        settings.version())
                != 1) CatalogService.conflict();
    }
}
