package com.signdesk.service.impl;

import com.signdesk.common.*;
import com.signdesk.converter.ResponseConverter;
import com.signdesk.domain.vo.*;
import com.signdesk.mapper.*;
import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RunRecordServiceImpl implements IRunRecordService {
    private final RunRecordMapper records;
    private final RunResponseMapper responses;
    private final ISettingsService settings;
    private final Clock clock;

    @Override
    public List<BatchVo> active() {
        return records.active();
    }

    @Override
    public BatchVo queryBatch(String id) {
        var batch = Checks.found(records.queryBatch(id));
        batch.setItems(records.batchItems(id));
        return batch;
    }

    @Override
    public PageVo<RunLogVo> queryPageList(
            String platformId, String status, String source, int page, int size) {
        var paging = new PageQuery(page, size);
        platformId = blankToNull(platformId);
        status = blankToNull(status);
        source = blankToNull(source);
        long total = records.countLogs(platformId, status, source);
        return new PageVo<>(
                records.queryLogs(platformId, status, source, paging.size(), paging.offset()),
                total,
                paging.page(),
                paging.size());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public RunDetailVo queryById(String id) {
        var detail = Checks.found(records.queryDetail(id));
        detail.setResponse(queryResponse(id));
        return detail;
    }

    @Override
    public ResponseDetailVo queryResponse(String id) {
        return ResponseConverter.detail(responses.selectById(id));
    }

    @Override
    @Transactional
    public void clearExpired() {
        records.clearExpired(
                clock.instant().minusSeconds(settings.query().retentionDays() * 86400L).toString());
        // BLOBs cascade with execution items; day markers and batch occurrence ledger remain
        // independent.
    }
}
