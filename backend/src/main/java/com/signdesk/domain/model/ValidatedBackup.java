package com.signdesk.domain.model;

import com.signdesk.domain.vo.BackupFile;
import com.signdesk.engine.RequestSpec;

import java.util.List;

public record ValidatedBackup(BackupFile file, List<PreparedRequest> requests) {
    public record PreparedRequest(BackupFile.RequestEntry entry, RequestSpec spec) {
        @Override
        public String toString() {
            return "PreparedRequest[redacted]";
        }
    }

    @Override
    public String toString() {
        return "ValidatedBackup[redacted]";
    }
}
