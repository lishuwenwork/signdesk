package com.signdesk.converter;

import com.signdesk.common.Checks;
import com.signdesk.common.Json;
import com.signdesk.domain.PlatformSchedule;
import com.signdesk.domain.vo.ScheduleVo;
import com.signdesk.schedule.ScheduleSpec;

import java.util.List;

public final class ScheduleConverter {
    private ScheduleConverter() {}

    @SuppressWarnings("unchecked")
    public static ScheduleVo toVo(PlatformSchedule r) {
        return new ScheduleVo(
                Checks.flag(r.getEnabled()),
                r.getFrequency(),
                Json.read(r.getWeekdaysJson(), List.class),
                Json.read(r.getTimesJson(), List.class),
                r.getTimezone(),
                r.getIntervalSeconds(),
                r.getCatchupMinutes(),
                Checks.flag(r.getSkipCompletedDaily()),
                r.getRevision());
    }

    public static PlatformSchedule toEntity(String id, ScheduleSpec s, String effectiveFrom) {
        var r = new PlatformSchedule();
        r.setPlatformId(id);
        r.setRevision(s.revision());
        r.setEnabled(s.enabled() ? 1 : 0);
        r.setFrequency(s.frequency());
        r.setWeekdaysJson(Json.write(s.weekdays()));
        r.setTimesJson(Json.write(s.times()));
        r.setTimezone(s.timezone());
        r.setIntervalSeconds(s.intervalSeconds());
        r.setCatchupMinutes(s.catchupMinutes());
        r.setSkipCompletedDaily(s.skipCompletedDaily() ? 1 : 0);
        r.setEffectiveFrom(effectiveFrom);
        return r;
    }

    public static PlatformSchedule initial(String id, String now) {
        return toEntity(
                id,
                new ScheduleSpec(
                        false,
                        "daily",
                        List.of(1, 2, 3, 4, 5, 6, 7),
                        List.of("09:00"),
                        "Asia/Shanghai",
                        2,
                        0,
                        true,
                        1),
                now);
    }
}
