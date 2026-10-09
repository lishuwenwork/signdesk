package com.signdesk.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RequestDayState {
    private String requestId;
    private String businessDate;
    private Integer unknownPending;
}
