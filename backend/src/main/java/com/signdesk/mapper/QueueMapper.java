package com.signdesk.mapper;

import com.signdesk.domain.*;
import com.signdesk.domain.model.RunGate;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QueueMapper {
    long activeForPlatform(String id);

    long activeForAccount(String id);

    long activeForRequest(String id);

    long activeCount();

    String platformForRequest(String id);

    List<RequestDefinition> eligible(
            @Param("platformId") String platformId, @Param("requestId") String requestId);

    List<RunBatch> queuedBatches();

    int claimBatch(String id);

    RunGate gate(
            @Param("requestId") String requestId,
            @Param("batchId") String batchId,
            @Param("date") String date);

    int skip(
            @Param("id") String id,
            @Param("status") String status,
            @Param("summary") String summary,
            @Param("now") String now);

    int start(@Param("id") String id, @Param("now") String now);

    int complete(
            @Param("id") String id,
            @Param("status") String status,
            @Param("httpStatus") Integer httpStatus,
            @Param("durationMs") long durationMs,
            @Param("summary") String summary,
            @Param("now") String now);

    int saveCompletion(
            @Param("requestId") String requestId,
            @Param("date") String date,
            @Param("runId") String runId);

    int savePending(
            @Param("requestId") String requestId,
            @Param("date") String date,
            @Param("pending") int pending);

    int pauseCredentials(@Param("requestId") String requestId, @Param("revision") int revision);

    int recoverPending();

    int recoverItems(String now);

    int recoverBatches();

    int unfinishedPending(String batchId);

    int unfinishedItems(@Param("batchId") String batchId, @Param("now") String now);

    int finishBatch(String id);

    int cancelBatch(String id);

    int cancelItems(@Param("batchId") String batchId, @Param("now") String now);

    int finishCancelledBatch(String id);
}
