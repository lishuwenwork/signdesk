package com.signdesk.domain.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BatchItemVo {
    private String id;
    private String requestId;
    private Integer requestRevision;
    private String status;
    private String safeSummary;
    private String name;
    private String alias;
}
