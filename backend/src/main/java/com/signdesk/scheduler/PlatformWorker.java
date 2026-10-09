package com.signdesk.scheduler;

import com.signdesk.common.Json;
import com.signdesk.domain.RunBatch;
import com.signdesk.domain.vo.RevisionVo;
import com.signdesk.engine.*;
import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.*;

@Component
@RequiredArgsConstructor
public class PlatformWorker {
    private final IRunService runs;
    private final IRequestService requests;
    private final ISettingsService settings;
    private final HutoolRequestExecutor http;
    private final RunCoordinator coordinator;
    private final Clock clock;

    public void execute(RunBatch batch) throws InterruptedException {
        boolean sent = false;
        boolean windowExpired =
                batch.getSource().equals("auto")
                        && clock.instant().isAfter(Instant.parse(batch.getExpiresAt()));
        for (var item : runs.queuedItems(batch.getId())) {
            if (coordinator.closed()) break;
            if (sent) {
                for (int i = 0; i < batch.getIntervalSeconds() && !coordinator.closed(); i++) {
                    if (runs.cancelled(batch.getId())) break;
                    Thread.sleep(1000);
                }
            }
            if (coordinator.closed()) break;
            if (!runs.prepare(batch, item, windowExpired)) continue;
            HutoolRequestExecutor.Result result;
            RevisionVo revision;
            ResultRules rules;
            try {
                revision = requests.queryRevision(item.getRequestId(), item.getRequestRevision());
                rules = Json.read(item.getRulesJson(), ResultRules.class);
            } catch (RuntimeException e) {
                persist(
                        item,
                        batch,
                        new HutoolRequestExecutor.Result("failed", null, 0, "请求快照不可用，未发送"));
                continue;
            }
            var current = settings.query();
            try {
                // Neither this call nor the interval wait has a database transaction.
                result =
                        http.execute(
                                revision.spec(), rules, current.timeoutSeconds(), current.proxy());
            } catch (Exception e) {
                result = new HutoolRequestExecutor.Result("unknown", null, 0, "执行中断，发送结果待确认");
            }
            sent = true;
            persist(item, batch, result);
        }
    }

    private void persist(
            com.signdesk.domain.RunItem item, RunBatch batch, HutoolRequestExecutor.Result result) {
        RuntimeException last = null;
        // Only the already-captured result is retried. No retry path calls the HTTP executor.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                runs.persistResult(item, batch, result);
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }
}
