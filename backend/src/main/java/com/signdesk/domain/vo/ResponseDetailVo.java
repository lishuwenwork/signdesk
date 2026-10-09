package com.signdesk.domain.vo;

public record ResponseDetailVo(
        String state,
        String body,
        String encoding,
        String contentType,
        String charset,
        int byteLength,
        int limitBytes) {

    @Override
    public String toString() {
        return "ResponseDetailVo[redacted]";
    }
}
