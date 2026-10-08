package com.signdesk.engine;

public record ResponseBody(
        byte[] bodyBytes, String contentType, String charset, boolean complete, boolean truncated) {
    public ResponseBody {
        bodyBytes = bodyBytes.clone();
    }

    @Override
    public byte[] bodyBytes() {
        return bodyBytes.clone();
    }

    @Override
    public String toString() {
        return "ResponseBody[bytes=" + bodyBytes.length + ", complete=" + complete
                + ", truncated=" + truncated + "]";
    }
}
