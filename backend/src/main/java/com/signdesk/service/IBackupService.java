package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

public interface IBackupService {
    BackupFile export(BackupExportBo input);

    BackupPreviewVo preview(BackupPreviewBo input);

    void restore(BackupImportBo input);
}
