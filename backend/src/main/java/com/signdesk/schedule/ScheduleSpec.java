package com.signdesk.schedule;

import com.signdesk.common.ApiException;

import java.time.*;
import java.util.List;
import java.util.Set;

public record ScheduleSpec(
        boolean enabled,
        String frequency,
        List<Integer> weekdays,
        List<String> times,
        String timezone,
        int intervalSeconds,
        int catchupMinutes,
        boolean skipCompletedDaily,
        int revision) {
    public ScheduleSpec validated() {
        if (!Set.of("daily", "weekly").contains(frequency)
                || !Set.of("Asia/Shanghai", "UTC").contains(timezone))
            throw new ApiException("计划频率或时区不支持");
        if (times == null
                || times.isEmpty()
                || times.size() > 24
                || times.stream()
                        .anyMatch(t -> t == null || !t.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")))
            throw new ApiException("计划需设置 1～24 个 HH:mm 时间点");
        if (weekdays == null
                || weekdays.stream().anyMatch(d -> d == null || d < 1 || d > 7)
                || frequency.equals("weekly") && weekdays.isEmpty())
            throw new ApiException("请选择星期一至星期日");
        if (intervalSeconds < 0
                || intervalSeconds > 60
                || catchupMinutes < 0
                || catchupMinutes > 1440) throw new ApiException("请求间隔应为 0～60 秒，补执行窗口应为 0～1440 分钟");
        return new ScheduleSpec(
                enabled,
                frequency,
                weekdays.stream().distinct().sorted().toList(),
                times.stream().distinct().sorted().toList(),
                timezone,
                intervalSeconds,
                catchupMinutes,
                skipCompletedDaily,
                revision);
    }

    public List<Instant> due(Instant now, Instant effective) {
        ZonedDateTime local = now.atZone(ZoneId.of(timezone));
        if (!enabled
                || frequency.equals("weekly")
                        && !weekdays.contains(local.getDayOfWeek().getValue())) return List.of();
        long window = Math.max(30, catchupMinutes * 60L);
        return times.stream()
                .map(
                        t ->
                                local.toLocalDate()
                                        .atTime(LocalTime.parse(t))
                                        .atZone(local.getZone())
                                        .toInstant())
                .filter(
                        t ->
                                !t.isBefore(effective)
                                        && !t.isAfter(now)
                                        && !t.isBefore(now.minusSeconds(window)))
                .sorted()
                .toList();
    }

    public Instant next(Instant now) {
        if (!enabled) return null;
        ZoneId zone = ZoneId.of(timezone);
        for (int i = 0; i < 8; i++) {
            LocalDate date = now.atZone(zone).toLocalDate().plusDays(i);
            if (frequency.equals("weekly") && !weekdays.contains(date.getDayOfWeek().getValue()))
                continue;
            for (String time : times) {
                Instant at = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant();
                if (at.isAfter(now)) return at;
            }
        }
        return null;
    }
}
