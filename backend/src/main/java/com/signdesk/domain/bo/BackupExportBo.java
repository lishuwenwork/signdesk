package com.signdesk.domain.bo;


public record BackupExportBo(Boolean includeRequests) {
    @Override
    public String toString() {
        return "BackupExportBo[redacted]";
    }
}
