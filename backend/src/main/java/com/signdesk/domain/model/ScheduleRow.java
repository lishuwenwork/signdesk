package com.signdesk.domain.model;

import com.signdesk.domain.PlatformSchedule;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ScheduleRow extends PlatformSchedule {
    private String name;
    private Integer platformEnabled;
}
