package com.signdesk.service.impl;

import com.signdesk.domain.vo.*;
import com.signdesk.mapper.SystemMapper;
import com.signdesk.scheduler.RunCoordinator;
import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.*;

@Service
@RequiredArgsConstructor
public class SystemServiceImpl implements ISystemService {
    private final SystemMapper system;
    private final ISettingsService settings;
    private final IScheduleService schedules;
    private final IRunRecordService records;
    private final RunCoordinator coordinator;
    private final Clock clock;

    @Override
    public SystemStatusVo status() {
        return new SystemStatusVo(
                coordinator.ready(),
                settings.query().paused(),
                records.active().size(),
                clock.instant().toString(),
                "0.1.0");
    }

    @Override
    public DashboardVo dashboard() {
        var now = clock.instant();
        String date = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        var counts = system.counts(date, now.atZone(ZoneId.of("UTC")).toLocalDate().toString());
        return new DashboardVo(
                date,
                counts.getPlatforms(),
                counts.getAccounts(),
                counts.getRequests(),
                counts.getCompleted(),
                counts.getNeedsAttention(),
                records.active(),
                schedules.queryList(),
                records.queryPageList(null, null, null, 1, 6).items());
    }
}
