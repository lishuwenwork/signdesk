package com.signdesk.controller;

import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.service.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RequestController {
    private final IRequestService service;

    @PostMapping("/requests/preview")
    public CurlPreviewVo preview(@Valid @RequestBody CurlPreviewBo b) {
        return service.preview(b);
    }

    @PostMapping("/accounts/{id}/requests")
    public IdVo insert(@PathVariable String id, @Valid @RequestBody NewRequestBo b) {
        return new IdVo(service.insert(id, b));
    }

    @PutMapping("/requests/{id}")
    public SavedVo update(@PathVariable String id, @Valid @RequestBody RequestBo b) {
        service.update(id, b);
        return new SavedVo(true);
    }

    @PostMapping("/requests/{id}/revisions")
    public RevisionSavedVo replaceCurl(@PathVariable String id, @Valid @RequestBody CurlBo b) {
        return new RevisionSavedVo(service.replaceCurl(id, b));
    }

    @GetMapping("/requests/{id}/revision")
    public RevisionVo revision(
            @PathVariable String id, @RequestParam(required = false) Integer revision) {
        return service.queryRevision(id, revision);
    }

    @DeleteMapping("/requests/{id}")
    public DeletedVo delete(@PathVariable String id) {
        service.delete(id);
        return new DeletedVo(true);
    }

    @PostMapping("/rules/test")
    public RuleTestVo rules(@Valid @RequestBody RuleTestBo b) {
        return service.testRules(b);
    }
}
