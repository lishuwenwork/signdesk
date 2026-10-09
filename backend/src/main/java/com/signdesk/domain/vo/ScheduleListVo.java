package com.signdesk.domain.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ScheduleListVo {
    private String platformId;
    private Integer revision;
    private Integer enabled;
    private String frequency;
    private String timezone;
    private Integer intervalSeconds;
    private Integer catchupMinutes;
    private Integer skipCompletedDaily;
    private String effectiveFrom;
    private String name;
    private Integer platformEnabled;
    private ScheduleVo spec;
    private String nextAt;
}
