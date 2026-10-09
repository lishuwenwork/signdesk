package com.signdesk.domain.vo;

import com.fasterxml.jackson.annotation.*;
import com.signdesk.engine.ResultRules;
import com.signdesk.schedule.ScheduleSpec;

import java.util.List;

public record BackupFile(
        @JsonProperty(required = true) String format,
        @JsonProperty(required = true) Payload payload) {
    @Override
    public String toString() {
        return "BackupFile[redacted]";
    }

    public record Payload(
            @JsonProperty(required = true) boolean includesRequests,
            @JsonProperty(required = true) Settings settings,
            @JsonProperty(required = true) List<PlatformEntry> platforms,
            @JsonProperty(required = true) List<AccountEntry> accounts,
            @JsonProperty(required = true) List<RequestEntry> requests,
            @JsonProperty(required = true) List<TemplateEntry> templates,
            @JsonProperty(required = true) List<ScheduleEntry> schedules,
            @JsonProperty(required = true) List<DayMarker> completed,
            @JsonProperty(required = true) List<DayMarker> pending) {
        @Override
        public String toString() {
            return "Payload[redacted]";
        }
    }

    public record Settings(
            @JsonProperty(required = true) boolean paused,
            @JsonProperty(required = true) int concurrency,
            @JsonProperty(required = true) int timeoutSeconds,
            @JsonProperty(required = true) int retentionDays,
            @JsonProperty(required = true) int version,
            @JsonProperty(required = true) Proxy proxy) {
        @Override
        public String toString() {
            return "Settings[redacted]";
        }
    }

    public record Proxy(
            @JsonProperty(required = true) String mode,
            @JsonProperty(required = true) String host,
            @JsonProperty(required = true) int port) {
        @Override
        public String toString() {
            return "Proxy[redacted]";
        }
    }

    public record PlatformEntry(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String name,
            @JsonProperty(required = true) String note,
            @JsonProperty(required = true) boolean enabled,
            @JsonProperty(required = true) int sortOrder) {
        @Override
        public String toString() {
            return "PlatformEntry[redacted]";
        }
    }

    public record AccountEntry(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String platformId,
            @JsonProperty(required = true) String alias,
            @JsonProperty(required = true) boolean enabled,
            @JsonProperty(required = true) int sortOrder) {
        @Override
        public String toString() {
            return "AccountEntry[redacted]";
        }
    }

    public record RequestEntry(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String accountId,
            @JsonProperty(required = true) String name,
            @JsonProperty(required = true) boolean enabled,
            @JsonProperty(required = true) boolean authPaused,
            @JsonProperty(required = true) int sortOrder,
            @JsonProperty(required = true) ResultRules rules,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rawCurl) {
        @Override
        public String toString() {
            return "RequestEntry[redacted]";
        }
    }

    public record TemplateEntry(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String platformId,
            @JsonProperty(required = true) String name,
            @JsonProperty(required = true) ResultRules rules) {
        @Override
        public String toString() {
            return "TemplateEntry[redacted]";
        }
    }

    public record ScheduleEntry(
            @JsonProperty(required = true) String platformId,
            @JsonProperty(required = true) Schedule spec) {
        @Override
        public String toString() {
            return "ScheduleEntry[redacted]";
        }
    }

    public record Schedule(
            @JsonProperty(required = true) boolean enabled,
            @JsonProperty(required = true) String frequency,
            @JsonProperty(required = true) List<Integer> weekdays,
            @JsonProperty(required = true) List<String> times,
            @JsonProperty(required = true) String timezone,
            @JsonProperty(required = true) int intervalSeconds,
            @JsonProperty(required = true) int catchupMinutes,
            @JsonProperty(required = true) boolean skipCompletedDaily,
            @JsonProperty(required = true) int revision) {
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
            return "Schedule[redacted]";
        }
    }

    public record DayMarker(
            @JsonProperty(required = true) String requestId,
            @JsonProperty(required = true) String businessDate) {
        @Override
        public String toString() {
            return "DayMarker[redacted]";
        }
    }
}
