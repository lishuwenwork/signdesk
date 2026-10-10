package com.signdesk.domain.vo;

import com.signdesk.engine.ResultRules;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RequestVo {
    private String id;
    private String accountId;
    private String name;
    private Integer enabled;
    private Integer currentRevision;
    private ResultRules rules;
    private Integer authPaused;
    private Integer sortOrder;
    private Integer version;
    private String safeHost;
    private String method;
    private String todayState = "none";
    private LastRunVo lastRun;
}
