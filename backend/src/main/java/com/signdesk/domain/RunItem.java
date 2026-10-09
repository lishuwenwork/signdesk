package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("run_items")
public class RunItem {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String batchId;
    private String requestId;
    private Integer requestRevision;
    private String rulesJson;
    private Integer ordinal;
    private String status;
    private Integer httpStatus;
    private Long durationMs;
    private String safeSummary;
    private String startedAt;
    private String finishedAt;
}
