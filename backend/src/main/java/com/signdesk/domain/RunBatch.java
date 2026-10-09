package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("run_batches")
public class RunBatch {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String platformId;
    private Integer scheduleRevision;
    private String scheduledAt;
    private String businessDate;
    private String timezone;
    private String source;
    private String manualKey;
    private Integer force;
    private Integer skipCompletedDaily;
    private Integer intervalSeconds;
    private String expiresAt;
    private String status;
    private Integer cancelRequested;
    private String createdAt;
}
