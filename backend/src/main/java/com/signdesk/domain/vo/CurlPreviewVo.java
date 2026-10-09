package com.signdesk.domain.vo;

public record CurlPreviewVo(com.signdesk.engine.RequestSpec spec, java.util.List<String> warnings) {

    @Override
    public String toString() {
        return "CurlPreviewVo[redacted]";
    }
}
