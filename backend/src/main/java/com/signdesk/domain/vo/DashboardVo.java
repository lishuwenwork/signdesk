package com.signdesk.domain.vo;

public record DashboardVo(
        String date,
        long platforms,
        long accounts,
        long requests,
        long completed,
        long needsAttention,
        java.util.List<BatchVo> active,
        java.util.List<ScheduleListVo> schedules,
        java.util.List<RunLogVo> recent) {

    @Override
    public String toString() {
        return "DashboardVo[redacted]";
    }
}
