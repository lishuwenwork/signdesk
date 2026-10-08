package com.signdesk.run;

import com.signdesk.common.ApiException;
import com.signdesk.platform.CatalogService;
import com.signdesk.storage.Db;

import org.springframework.stereotype.Service;

@Service
public class SettingsService {
    public record Settings(
            boolean paused, int concurrency, int timeoutSeconds, int retentionDays, int version) {}

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
                Db.integer(r, "version"));
    }

    public void save(Settings settings) {
        if (settings.concurrency() < 1
                || settings.concurrency() > 8
                || settings.timeoutSeconds() < 1
                || settings.timeoutSeconds() > 120
                || settings.retentionDays() < 1
                || settings.retentionDays() > 365)
            throw new ApiException("并发 1～8、超时 1～120 秒、日志保留 1～365 天");
        if (db.update(
                        "UPDATE settings SET"
                            + " paused=?,concurrency=?,timeout_seconds=?,retention_days=?,version=version+1"
                            + " WHERE id=1 AND version=?",
                        settings.paused() ? 1 : 0,
                        settings.concurrency(),
                        settings.timeoutSeconds(),
                        settings.retentionDays(),
                        settings.version())
                != 1) CatalogService.conflict();
    }
}
