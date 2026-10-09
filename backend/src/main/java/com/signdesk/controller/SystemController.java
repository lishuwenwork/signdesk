package com.signdesk.controller;

import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SystemController {
    private final ISystemService service;

    @GetMapping("/system/status")
    public SystemStatusVo status() {
        return service.status();
    }

    @GetMapping("/dashboard")
    public DashboardVo dashboard() {
        return service.dashboard();
    }
}
