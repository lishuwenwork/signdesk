package com.signdesk.domain.bo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.signdesk.domain.vo.BackupFile;

public record BackupPreviewBo(@JsonProperty(required = true) BackupFile backup) {
    @Override
    public String toString() {
        return "BackupPreviewBo[redacted]";
    }
}
