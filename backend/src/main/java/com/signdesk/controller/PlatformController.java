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
public class PlatformController {
    private final IPlatformService service;

    @GetMapping("/platforms")
    public List<PlatformVo> queryList() {
        return service.queryList();
    }

    @PostMapping("/platforms")
    public IdVo insert(@Valid @RequestBody PlatformBo b) {
        return new IdVo(service.insert(b));
    }

    @PutMapping("/platforms/{id}")
    public SavedVo update(@PathVariable String id, @Valid @RequestBody PlatformBo b) {
        service.update(id, b);
        return new SavedVo(true);
    }

    @DeleteMapping("/platforms/{id}")
    public DeletedVo delete(@PathVariable String id) {
        service.delete(id);
        return new DeletedVo(true);
    }
}
