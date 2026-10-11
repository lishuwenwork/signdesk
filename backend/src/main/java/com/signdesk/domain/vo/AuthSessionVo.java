package com.signdesk.domain.vo;

public record AuthSessionVo(boolean authenticated, String username) {
    @Override
    public String toString() {
        return "AuthSessionVo[redacted]";
    }
}
