package com.signdesk.mapper;

import com.signdesk.domain.vo.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RunRecordMapper {
    List<BatchVo> active();

    BatchVo queryBatch(String id);

    List<BatchItemVo> batchItems(String id);

    RunDetailVo queryDetail(String id);

    long countLogs(
            @Param("platformId") String platformId,
            @Param("status") String status,
            @Param("source") String source);

    List<RunLogVo> queryLogs(
            @Param("platformId") String platformId,
            @Param("status") String status,
            @Param("source") String source,
            @Param("size") int size,
            @Param("offset") long offset);

    int clearExpired(String before);
}
