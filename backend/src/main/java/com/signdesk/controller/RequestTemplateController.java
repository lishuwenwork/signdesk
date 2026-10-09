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
public class RequestTemplateController {
    private final IRequestTemplateService service;

    @GetMapping("/platforms/{platformId}/templates")
    public List<RequestTemplateVo> queryList(@PathVariable String platformId) {
        return service.queryList(platformId);
    }

    @PostMapping("/platforms/{platformId}/templates")
    public IdVo insert(
            @PathVariable String platformId, @Valid @RequestBody NewRequestTemplateBo b) {
        return new IdVo(service.insert(platformId, b));
    }

    @PutMapping("/platforms/{platformId}/templates/{id}")
    public SavedVo update(
            @PathVariable String platformId,
            @PathVariable String id,
            @Valid @RequestBody RequestTemplateBo b) {
        service.update(platformId, id, b);
        return new SavedVo(true);
    }

    @DeleteMapping("/platforms/{platformId}/templates/{id}")
    public DeletedVo delete(@PathVariable String platformId, @PathVariable String id) {
        service.delete(platformId, id);
        return new DeletedVo(true);
    }
}
