package com.signdesk.schedule;

import com.signdesk.common.Json;
import com.signdesk.platform.CatalogService;
import com.signdesk.storage.Db;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Service
public class ScheduleService {
    private final Db db;
    private final Clock clock;

    public ScheduleService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public ScheduleSpec get(String id) {
        return from(db.one("SELECT * FROM platform_schedules WHERE platform_id=?", id));
    }

    @SuppressWarnings("unchecked")
    public ScheduleSpec from(Map<String, Object> row) {
        return new ScheduleSpec(
                Db.flag(row, "enabled"),
                Db.text(row, "frequency"),
                Json.read(Db.text(row, "weekdaysJson"), List.class),
                Json.read(Db.text(row, "timesJson"), List.class),
                Db.text(row, "timezone"),
                Db.integer(row, "intervalSeconds"),
                Db.integer(row, "catchupMinutes"),
                Db.flag(row, "skipCompletedDaily"),
                Db.integer(row, "revision"));
    }

    @Transactional
    public void save(String id, ScheduleSpec supplied) {
        ScheduleSpec s = supplied.validated();
        if (db.update(
                        "UPDATE platform_schedules SET"
                            + " revision=revision+1,enabled=?,frequency=?,weekdays_json=?,times_json=?,timezone=?,interval_seconds=?,catchup_minutes=?,skip_completed_daily=?,effective_from=?"
                            + " WHERE platform_id=? AND revision=?",
                        s.enabled() ? 1 : 0,
                        s.frequency(),
                        Json.write(s.weekdays()),
                        Json.write(s.times()),
                        s.timezone(),
                        s.intervalSeconds(),
                        s.catchupMinutes(),
                        s.skipCompletedDaily() ? 1 : 0,
                        clock.instant().toString(),
                        id,
                        s.revision())
                != 1) CatalogService.conflict();
    }

    public List<Map<String, Object>> list() {
        var rows =
                db.rows(
                        "SELECT s.*,p.name,p.enabled AS platform_enabled FROM platform_schedules s"
                                + " JOIN platforms p ON p.id=s.platform_id ORDER BY p.rowid");
        for (var row : rows) {
            var s = from(row);
            row.put("spec", s);
            var next = Db.flag(row, "platformEnabled") ? s.next(clock.instant()) : null;
            row.put("nextAt", next == null ? null : next.toString());
            row.remove("weekdaysJson");
            row.remove("timesJson");
        }
        return rows;
    }
}
