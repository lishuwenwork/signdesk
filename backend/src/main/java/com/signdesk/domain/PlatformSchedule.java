package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("platform_schedules")
public class PlatformSchedule {
    @TableId(type = IdType.INPUT)
    private String platformId;

    @Version private Integer revision;
    private Integer enabled;
    private String frequency;
    private String weekdaysJson;
    private String timesJson;
    private String timezone;
    private Integer intervalSeconds;
    private Integer catchupMinutes;
    private Integer skipCompletedDaily;
    private String effectiveFrom;
}
