package com.signdesk.domain.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RunDetailVo {
    private String id;
    private String batchId;
    private String requestId;
    private Integer requestRevision;
    private String status;
    private Integer httpStatus;
    private Long durationMs;
    private String safeSummary;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
    private String businessDate;
    private String source;
    private ResponseDetailVo response;
}
