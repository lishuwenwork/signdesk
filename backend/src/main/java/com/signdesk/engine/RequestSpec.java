package com.signdesk.engine;

import java.util.List;

public record RequestSpec(
        String method,
        String rawUrl,
        List<Header> headers,
        byte[] bodyBytes,
        boolean followRedirects,
        int timeoutMillis) {
    public record Header(String name, String value) {
        @Override public String toString() { return "Header[redacted]"; }
    }

    public RequestSpec {
        headers = List.copyOf(headers);
        bodyBytes = bodyBytes.clone();
    }

    @Override public String toString() { return "RequestSpec[redacted]"; }

    @Override
    public byte[] bodyBytes() {
        return bodyBytes.clone();
    }
}
