package com.signdesk.backup;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @PostMapping("/export")
    public Object export(@RequestBody BackupService.Request request) {
        return service.export(request);
    }

    @PostMapping("/preview")
    public Object preview(@RequestBody BackupService.Request request) {
        return service.preview(request);
    }

    @PostMapping("/import")
    public Object restore(@RequestBody BackupService.Request request) {
        service.restore(request);
        return Map.of("restored", true, "paused", true);
    }
}
