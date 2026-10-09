package com.signdesk.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DailyCompletion {
    private String requestId;
    private String businessDate;
    private String completedRunId;
}
