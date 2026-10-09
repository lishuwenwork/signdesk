package com.signdesk.domain.model;

import com.signdesk.schedule.ScheduleSpec;

import java.time.Instant;

public record SchedulePlan(String platformId, ScheduleSpec spec, Instant effectiveFrom) {}
