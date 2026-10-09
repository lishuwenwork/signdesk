package com.signdesk.domain.bo;

public record ManualRunBo(String scope, String id, boolean force, String key) {

    @Override
    public String toString() {
        return "ManualRunBo[redacted]";
    }
}
