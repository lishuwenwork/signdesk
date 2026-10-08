package com.signdesk.schedule;

import com.signdesk.run.RunService;
import com.signdesk.run.SettingsService;
import com.signdesk.storage.Db;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class ScheduleScanner {
    private final Db db;
    private final ScheduleService schedules;
    private final RunService runs;
    private final SettingsService settings;
    private final Clock clock;

    public ScheduleScanner(
            Db db,
            ScheduleService schedules,
            RunService runs,
            SettingsService settings,
            Clock clock) {
        this.db = db;
        this.schedules = schedules;
        this.runs = runs;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${signdesk.scan-delay-ms:5000}")
    public void scan() {
        if (!runs.ready() || settings.get().paused()) return;
        Instant now = clock.instant();
        for (var row :
                db.rows(
                        "SELECT s.* FROM platform_schedules s JOIN platforms p ON"
                                + " p.id=s.platform_id WHERE s.enabled=1 AND p.enabled=1")) {
            var schedule = schedules.from(row);
            var due = schedule.due(now, Instant.parse(Db.text(row, "effectiveFrom")));
            // Daily sign-in mode coalesces missed slots to the latest effective occurrence.
            if (schedule.skipCompletedDaily() && !due.isEmpty())
                due = java.util.List.of(due.getLast());
            for (Instant at : due) runs.automatic(Db.text(row, "platformId"), schedule, at);
        }
    }

    @Scheduled(fixedDelay = 3600000)
    public void retention() {
        if (!runs.ready()) return;
        String before =
                clock.instant().minusSeconds(settings.get().retentionDays() * 86400L).toString();
        db.update(
                "DELETE FROM run_items WHERE finished_at IS NOT NULL AND finished_at<? AND status"
                        + " NOT IN('queued','running')",
                before);
        // Keep occurrence and completion ledgers separate: log cleanup never permits an automatic
        // replay.
    }
}
