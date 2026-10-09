package com.signdesk.domain.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DashboardCounts {
    private long platforms;
    private long accounts;
    private long requests;
    private long completed;
    private long needsAttention;
}
