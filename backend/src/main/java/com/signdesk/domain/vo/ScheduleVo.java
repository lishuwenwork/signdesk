package com.signdesk.domain.vo;

import com.signdesk.schedule.ScheduleSpec;

public record ScheduleVo(
        boolean enabled,
        String frequency,
        java.util.List<Integer> weekdays,
        java.util.List<String> times,
        String timezone,
        int intervalSeconds,
        int catchupMinutes,
        boolean skipCompletedDaily,
        int revision) {
    public ScheduleSpec toSpec() {
        return new ScheduleSpec(
                enabled,
                frequency,
                weekdays,
                times,
                timezone,
                intervalSeconds,
                catchupMinutes,
                skipCompletedDaily,
                revision);
    }

    @Override
    public String toString() {
        return "ScheduleVo[redacted]";
    }
}
