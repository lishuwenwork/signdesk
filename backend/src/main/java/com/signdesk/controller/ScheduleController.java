package com.signdesk.controller;

import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.service.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScheduleController {
    private final IScheduleService service;

    @GetMapping("/schedules")
    public List<ScheduleListVo> queryList() {
        return service.queryList();
    }

    @GetMapping("/platforms/{id}/schedule")
    public ScheduleVo queryById(@PathVariable String id) {
        return service.queryById(id);
    }

    @PutMapping("/platforms/{id}/schedule")
    public SavedVo save(@PathVariable String id, @Valid @RequestBody ScheduleBo b) {
        service.save(id, b);
        return new SavedVo(true);
    }
}
