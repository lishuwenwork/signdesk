package com.signdesk.controller;

import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SettingsController {
    private final ISettingsService service;

    @GetMapping("/settings")
    public SettingsVo query() {
        return service.query();
    }

    @PutMapping("/settings")
    public SavedVo save(@RequestBody SettingsBo b) {
        service.save(b);
        return new SavedVo(true);
    }
}
