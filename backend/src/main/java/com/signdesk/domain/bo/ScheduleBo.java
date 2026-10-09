package com.signdesk.domain.bo;

import com.signdesk.schedule.ScheduleSpec;

import jakarta.validation.constraints.NotBlank;

public record ScheduleBo(
        boolean enabled,
        @NotBlank String frequency,
        java.util.List<Integer> weekdays,
        java.util.List<String> times,
        @NotBlank String timezone,
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
        return "ScheduleBo[redacted]";
    }
}
