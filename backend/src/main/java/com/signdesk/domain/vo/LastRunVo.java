package com.signdesk.domain.vo;

import lombok.Getter;
import lombok.Setter;

/** Safe execution metadata only; request and response payloads are never selected. */
@Getter
@Setter
public class LastRunVo {
    private String id;
    private String status;
    private String finishedAt;
    private String createdAt;
    private Integer httpStatus;
    private Long durationMs;
}
