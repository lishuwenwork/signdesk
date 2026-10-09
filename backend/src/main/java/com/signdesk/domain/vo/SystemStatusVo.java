package com.signdesk.domain.vo;

public record SystemStatusVo(
        boolean ready, boolean paused, int activeBatches, String now, String version) {

    @Override
    public String toString() {
        return "SystemStatusVo[redacted]";
    }
}
