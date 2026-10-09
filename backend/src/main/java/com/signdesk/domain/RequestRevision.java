package com.signdesk.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RequestRevision {
    private String requestId;
    private Integer revision;
    private String rawCurl;
    private String specJson;
    private String createdAt;
}
