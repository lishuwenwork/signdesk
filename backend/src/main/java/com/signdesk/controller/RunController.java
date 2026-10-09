package com.signdesk.controller;

import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RunController {
    private final IRunService runs;
    private final IRunRecordService records;

    @PostMapping("/runs")
    public ResponseEntity<BatchIdsVo> execute(@RequestBody ManualRunBo b) {
        return ResponseEntity.accepted().body(new BatchIdsVo(runs.manual(b)));
    }

    @GetMapping("/batches")
    public List<BatchVo> active() {
        return records.active();
    }

    @GetMapping("/batches/{id}")
    public BatchVo batch(@PathVariable String id) {
        return records.queryBatch(id);
    }

    @PostMapping("/batches/{id}/cancel")
    public CancelledVo cancel(@PathVariable String id) {
        runs.cancel(id);
        return new CancelledVo(true);
    }

    @GetMapping("/runs")
    public PageVo<RunLogVo> logs(
            @RequestParam(required = false) String platformId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return records.queryPageList(platformId, status, source, page, size);
    }

    @GetMapping("/runs/{id}")
    public RunDetailVo detail(@PathVariable String id) {
        return records.queryById(id);
    }
}
