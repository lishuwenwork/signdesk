package com.signdesk.scheduler;

import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ScheduleScanner {
    private final IScheduleService schedules;
    private final IRunService runs;
    private final IRunRecordService records;
    private final ISettingsService settings;
    private final RunCoordinator coordinator;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${signdesk.scan-delay-ms:5000}")
    public void scan() {
        var generation = coordinator.scanGeneration();
        if (generation.isEmpty() || settings.query().paused()) return;
        Instant now = clock.instant();
        for (var plan : schedules.enabledPlans()) {
            var due = plan.spec().due(now, plan.effectiveFrom());
            if (plan.spec().skipCompletedDaily() && !due.isEmpty()) due = List.of(due.getLast());
            for (Instant at : due) runs.automatic(plan, at, generation.getAsLong());
        }
    }

    @Scheduled(fixedDelay = 3600000)
    public void retention() {
        if (coordinator.ready()) records.clearExpired();
    }
}
