package com.signdesk.domain.vo;

import com.signdesk.engine.RequestSpec;

public record RevisionVo(String rawCurl, RequestSpec spec) {

    @Override
    public String toString() {
        return "RevisionVo[redacted]";
    }
}
