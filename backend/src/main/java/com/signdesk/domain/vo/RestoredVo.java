package com.signdesk.domain.vo;

public record RestoredVo(boolean restored, boolean paused) {

    @Override
    public String toString() {
        return "RestoredVo[redacted]";
    }
}
