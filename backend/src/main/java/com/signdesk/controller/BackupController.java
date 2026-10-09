package com.signdesk.controller;

import com.signdesk.common.BackupCodec;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.service.*;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/backups")
@RequiredArgsConstructor
public class BackupController {
    private final IBackupService service;

    @PostMapping("/export")
    public BackupFile export(HttpServletRequest r) throws IOException {
        return service.export(BackupCodec.read(r.getInputStream(), BackupExportBo.class));
    }

    @PostMapping("/preview")
    public BackupPreviewVo preview(HttpServletRequest r) throws IOException {
        return service.preview(BackupCodec.read(r.getInputStream(), BackupPreviewBo.class));
    }

    @PostMapping("/import")
    public RestoredVo restore(HttpServletRequest r) throws IOException {
        service.restore(BackupCodec.read(r.getInputStream(), BackupImportBo.class));
        return new RestoredVo(true, true);
    }
}
