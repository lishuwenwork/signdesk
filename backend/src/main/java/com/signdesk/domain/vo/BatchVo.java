package com.signdesk.domain.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BatchVo {
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

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String name;

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long total;

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long done;

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.List<BatchItemVo> items;
}
