package com.signdesk.domain.bo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.signdesk.domain.vo.BackupFile;

public record BackupImportBo(@JsonProperty(required = true) BackupFile backup, Boolean replace) {
    @Override
    public String toString() {
        return "BackupImportBo[redacted]";
    }
}
