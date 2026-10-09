package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

import java.util.List;

public interface IRunService {
    List<String> manual(ManualRunBo input);

    void automatic(SchedulePlan plan, java.time.Instant scheduledAt, long scanGeneration);

    List<RunBatch> queuedBatches();

    boolean claimBatch(String id);

    List<RunItem> queuedItems(String batchId);

    boolean prepare(RunBatch batch, RunItem item, boolean windowExpired);

    boolean cancelled(String batchId);

    void persistResult(
            RunItem item, RunBatch batch, com.signdesk.engine.HutoolRequestExecutor.Result result);

    void finishBatch(RunBatch batch);

    void recover();

    void cancel(String id);

    void beginMaintenance();

    void endMaintenance();
}
