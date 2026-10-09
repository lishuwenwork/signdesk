package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

import java.util.List;

public interface IRunRecordService {
    List<BatchVo> active();

    BatchVo queryBatch(String id);

    PageVo<RunLogVo> queryPageList(
            String platformId, String status, String source, int page, int size);

    RunDetailVo queryById(String id);

    ResponseDetailVo queryResponse(String id);

    void clearExpired();
}
