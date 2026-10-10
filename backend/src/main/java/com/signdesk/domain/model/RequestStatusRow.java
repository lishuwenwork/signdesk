package com.signdesk.domain.model;

import com.signdesk.domain.vo.LastRunVo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RequestStatusRow {
    private String requestId;
    private String todayState;
    private LastRunVo lastRun;
}
