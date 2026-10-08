package com.signdesk.schedule;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class ScheduleSpecTest {
    ScheduleSpec daily(String time, int catchup) {
        return new ScheduleSpec(
                        true,
                        "daily",
                        List.of(1, 2, 3, 4, 5, 6, 7),
                        List.of(time),
                        "Asia/Shanghai",
                        2,
                        catchup,
                        true,
                        1)
                .validated();
    }

    @Test
    void independentPlatformsAndTimezones() {
        Instant nine = Instant.parse("2026-10-08T01:00:02Z"),
                early = Instant.parse("2026-10-08T00:00:00Z");
        assertEquals(
                List.of(Instant.parse("2026-10-08T01:00:00Z")), daily("09:00", 0).due(nine, early));
        assertTrue(daily("10:00", 0).due(nine, early).isEmpty());
        assertEquals(Instant.parse("2026-10-08T02:00:00Z"), daily("10:00", 0).next(nine));
    }

    @Test
    void catchupDoesNotGoBackMultipleDaysOrBeforeRevision() {
        var s = daily("09:00", 120);
        assertEquals(
                1,
                s.due(Instant.parse("2026-10-08T02:59:00Z"), Instant.parse("2026-10-07T00:00:00Z"))
                        .size());
        assertTrue(
                s.due(Instant.parse("2026-10-08T03:01:00Z"), Instant.parse("2026-10-07T00:00:00Z"))
                        .isEmpty());
        assertTrue(
                s.due(Instant.parse("2026-10-08T02:00:00Z"), Instant.parse("2026-10-08T01:01:00Z"))
                        .isEmpty());
        assertTrue(
                daily("09:00", 0)
                        .due(
                                Instant.parse("2026-10-08T01:01:00Z"),
                                Instant.parse("2026-10-07T00:00:00Z"))
                        .isEmpty());
    }

    @Test
    void weekdaysApplyToPlatformBusinessDate() {
        var s =
                new ScheduleSpec(
                                true,
                                "weekly",
                                List.of(5),
                                List.of("09:00"),
                                "Asia/Shanghai",
                                0,
                                0,
                                true,
                                1)
                        .validated();
        assertEquals(
                Instant.parse("2026-10-09T01:00:00Z"),
                s.next(Instant.parse("2026-10-08T01:00:00Z")));
    }
}
