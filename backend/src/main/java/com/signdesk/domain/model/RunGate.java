package com.signdesk.domain.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RunGate {
    private Integer cancelRequested;
    private Integer platformEnabled;
    private Integer accountEnabled;
    private Integer enabled;
    private Integer authPaused;
    private Integer completed;
    private Integer unknownPending;
}
