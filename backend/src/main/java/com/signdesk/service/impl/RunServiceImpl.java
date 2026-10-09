package com.signdesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.signdesk.common.*;
import com.signdesk.converter.ScheduleConverter;
import com.signdesk.domain.*;
import com.signdesk.domain.bo.ManualRunBo;
import com.signdesk.domain.model.SchedulePlan;
import com.signdesk.engine.HutoolRequestExecutor;
import com.signdesk.mapper.*;
import com.signdesk.schedule.ScheduleSpec;
import com.signdesk.scheduler.RunCoordinator;
import com.signdesk.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RunServiceImpl implements IRunService {
    private final PlatformMapper platforms;
    private final RunBatchMapper batches;
    private final RunItemMapper items;
    private final RunResponseMapper responses;
    private final QueueMapper queue;
    private final IScheduleService schedules;
    private final PlatformScheduleMapper scheduleMapper;
    private final ISettingsService settings;
    private final RunCoordinator coordinator;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    public List<String> manual(ManualRunBo input) {
        if (input.key() == null || !input.key().matches("[a-zA-Z0-9-]{8,100}"))
            throw new ApiException("执行请求需要有效的幂等 key");
        if (input.scope() == null || !List.of("all", "platform", "request").contains(input.scope()))
            throw new ApiException("执行范围不正确");
        if (!input.scope().equals("all") && (input.id() == null || input.id().isBlank()))
            throw new ApiException("执行对象不能为空");
        return coordinator.enqueue(
                () ->
                        transactions.execute(
                                tx -> {
                                    List<String> platformIds;
                                    if (input.scope().equals("all"))
                                        platformIds =
                                                platforms
                                                        .selectList(
                                                                new LambdaQueryWrapper<Platform>()
                                                                        .eq(Platform::getEnabled, 1)
                                                                        .orderByAsc(
                                                                                Platform::getId))
                                                        .stream()
                                                        .map(Platform::getId)
                                                        .toList();
                                    else if (input.scope().equals("platform"))
                                        platformIds = List.of(input.id());
                                    else
                                        platformIds =
                                                List.of(
                                                        Checks.found(
                                                                queue.platformForRequest(
                                                                        input.id())));
                                    var result = new ArrayList<String>();
                                    for (String platformId : platformIds) {
                                        String key = input.key() + ":" + platformId;
                                        var existing =
                                                batches.selectOne(
                                                        new LambdaQueryWrapper<RunBatch>()
                                                                .eq(RunBatch::getManualKey, key));
                                        if (existing != null) {
                                            result.add(existing.getId());
                                            continue;
                                        }
                                        var platform =
                                                Checks.found(platforms.selectById(platformId));
                                        if (!Checks.flag(platform.getEnabled()))
                                            throw new ApiException(409, "平台已停用，请先启用");
                                        var eligible =
                                                queue.eligible(
                                                        platformId,
                                                        input.scope().equals("request")
                                                                ? input.id()
                                                                : null);
                                        if (eligible.isEmpty()) continue;
                                        result.add(
                                                enqueue(
                                                        platformId,
                                                        schedules.queryById(platformId).toSpec(),
                                                        null,
                                                        key,
                                                        input.force(),
                                                        eligible));
                                    }
                                    if (result.isEmpty())
                                        throw new ApiException(409, "没有可执行的请求，或请求已在队列中");
                                    return result;
                                }));
    }

    @Override
    public void automatic(SchedulePlan plan, Instant at, long scanGeneration) {
        coordinator.automatic(
                scanGeneration,
                () ->
                        transactions.executeWithoutResult(
                                tx -> {
                                    // The lock excludes replacement; this transaction makes the
                                    // latest pause/plan checks and enqueue one consistent decision.
                                    if (!currentOccurrence(plan, at)) return;
                                    String platformId = plan.platformId();
                                    if (batches.selectCount(
                                                    new LambdaQueryWrapper<RunBatch>()
                                                            .eq(RunBatch::getPlatformId, platformId)
                                                            .eq(
                                                                    RunBatch::getScheduledAt,
                                                                    at.toString())
                                                            .eq(RunBatch::getSource, "auto"))
                                            == 0)
                                        enqueue(
                                                platformId,
                                                plan.spec(),
                                                at,
                                                null,
                                                false,
                                                queue.eligible(platformId, null));
                                }));
    }

    private boolean currentOccurrence(SchedulePlan expected, Instant at) {
        if (settings.query().paused()) return false;
        var platform = platforms.selectById(expected.platformId());
        var current = scheduleMapper.selectById(expected.platformId());
        if (platform == null
                || !Checks.flag(platform.getEnabled())
                || current == null
                || !Checks.flag(current.getEnabled())) return false;
        var spec = ScheduleConverter.toVo(current).toSpec();
        var effective = Instant.parse(current.getEffectiveFrom());
        if (!spec.equals(expected.spec()) || !effective.equals(expected.effectiveFrom()))
            return false;
        // A slow scan must not enqueue an occurrence outside the current date/window/effectivity.
        // This validates new automatic work only; persisted batches retain their frozen semantics.
        return spec.due(clock.instant(), effective).contains(at);
    }

    private String enqueue(
            String platformId,
            ScheduleSpec s,
            Instant at,
            String key,
            boolean force,
            List<RequestDefinition> eligible) {
        if (queue.activeCount() + eligible.size() > 10000)
            throw new ApiException(409, "队列已达上限，请等待完成");
        Instant now = clock.instant();
        var b = new RunBatch();
        b.setId(Ids.next());
        b.setPlatformId(platformId);
        b.setScheduleRevision(s.revision());
        b.setScheduledAt(at == null ? null : at.toString());
        b.setBusinessDate(
                (at == null ? now : at).atZone(ZoneId.of(s.timezone())).toLocalDate().toString());
        b.setTimezone(s.timezone());
        b.setSource(at == null ? "manual" : "auto");
        b.setManualKey(key);
        b.setForce(force ? 1 : 0);
        b.setSkipCompletedDaily(s.skipCompletedDaily() ? 1 : 0);
        b.setIntervalSeconds(s.intervalSeconds());
        b.setExpiresAt(
                at == null
                        ? null
                        : at.plusSeconds(Math.max(30, s.catchupMinutes() * 60L)).toString());
        b.setStatus(eligible.isEmpty() ? "completed" : "queued");
        b.setCancelRequested(0);
        b.setCreatedAt(now.toString());
        batches.insert(b);
        int order = 0;
        for (var r : eligible) {
            var i = new RunItem();
            i.setId(Ids.next());
            i.setBatchId(b.getId());
            i.setRequestId(r.getId());
            i.setRequestRevision(r.getCurrentRevision());
            i.setRulesJson(r.getRulesJson());
            i.setOrdinal(order++);
            i.setStatus("queued");
            i.setSafeSummary("");
            items.insert(i);
        }
        return b.getId();
    }

    @Override
    public List<RunBatch> queuedBatches() {
        return queue.queuedBatches();
    }

    @Override
    public boolean claimBatch(String id) {
        return Boolean.TRUE.equals(transactions.execute(tx -> queue.claimBatch(id) == 1));
    }

    @Override
    public List<RunItem> queuedItems(String batchId) {
        return items.selectList(
                new LambdaQueryWrapper<RunItem>()
                        .eq(RunItem::getBatchId, batchId)
                        .eq(RunItem::getStatus, "queued")
                        .orderByAsc(RunItem::getOrdinal));
    }

    @Override
    public boolean cancelled(String id) {
        return Checks.flag(Checks.found(batches.selectById(id)).getCancelRequested());
    }

    @Override
    public boolean prepare(RunBatch batch, RunItem item, boolean windowExpired) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        tx -> {
                            var fresh =
                                    Checks.found(
                                            queue.gate(
                                                    item.getRequestId(),
                                                    batch.getId(),
                                                    batch.getBusinessDate()));
                            String skip = null;
                            if (Checks.flag(fresh.getCancelRequested())) skip = "取消剩余队列";
                            else if (!Checks.flag(fresh.getPlatformEnabled())
                                    || !Checks.flag(fresh.getAccountEnabled())
                                    || !Checks.flag(fresh.getEnabled())) skip = "平台、账号或请求已停用";
                            else if (Checks.flag(fresh.getAuthPaused())) skip = "凭证过期，需更新 cURL";
                            else if (batch.getSource().equals("auto")
                                    && (windowExpired
                                            || !batch.getBusinessDate()
                                                    .equals(
                                                            clock.instant()
                                                                    .atZone(
                                                                            ZoneId.of(
                                                                                    batch
                                                                                            .getTimezone()))
                                                                    .toLocalDate()
                                                                    .toString())))
                                skip = "补执行窗口已过期";
                            else if (!Checks.flag(batch.getForce())) {
                                if (Checks.flag(batch.getSkipCompletedDaily())
                                        && Checks.flag(fresh.getCompleted())) skip = "今日已完成";
                                else if (Checks.flag(fresh.getUnknownPending()))
                                    skip = "本周期有待确认结果，请先核对后主动重新执行";
                            }
                            if (skip != null) {
                                queue.skip(
                                        item.getId(),
                                        skip.startsWith("取消") ? "cancelled" : "skipped",
                                        skip,
                                        clock.instant().toString());
                                return false;
                            }
                            // Returning true happens only after this transaction commits running.
                            return queue.start(item.getId(), clock.instant().toString()) == 1;
                        }));
    }

    @Override
    public void persistResult(RunItem item, RunBatch batch, HutoolRequestExecutor.Result result) {
        transactions.executeWithoutResult(
                tx -> {
                    if (queue.complete(
                                    item.getId(),
                                    result.status(),
                                    result.httpStatus(),
                                    result.durationMs(),
                                    result.summary(),
                                    clock.instant().toString())
                            != 1) throw new IllegalStateException("执行状态已改变，不能覆盖");
                    var response = new RunResponse();
                    response.setRunId(item.getId());
                    var body = result.response();
                    if (body == null) response.setCaptureState("unavailable");
                    else {
                        response.setBodyBytes(body.bodyBytes());
                        response.setContentType(body.contentType());
                        response.setCharset(body.charset());
                        response.setCaptureState(
                                body.truncated()
                                        ? "truncated"
                                        : body.complete() ? "complete" : "partial");
                    }
                    responses.insert(response);
                    if (List.of("success", "already_done").contains(result.status()))
                        queue.saveCompletion(
                                item.getRequestId(), batch.getBusinessDate(), item.getId());
                    if (result.status().equals("expired"))
                        queue.pauseCredentials(item.getRequestId(), item.getRequestRevision());
                    queue.savePending(
                            item.getRequestId(),
                            batch.getBusinessDate(),
                            result.status().equals("unknown") ? 1 : 0);
                });
    }

    @Override
    public void finishBatch(RunBatch batch) {
        transactions.executeWithoutResult(
                tx -> {
                    queue.unfinishedPending(batch.getId());
                    queue.unfinishedItems(batch.getId(), clock.instant().toString());
                    queue.finishBatch(batch.getId());
                });
    }

    @Override
    public void recover() {
        transactions.executeWithoutResult(
                tx -> {
                    queue.recoverPending();
                    queue.recoverItems(clock.instant().toString());
                    queue.recoverBatches();
                });
    }

    @Override
    public void cancel(String id) {
        transactions.executeWithoutResult(
                tx -> {
                    if (queue.cancelBatch(id) != 1) throw new ApiException(404, "批次不存在");
                    queue.cancelItems(id, clock.instant().toString());
                    queue.finishCancelledBatch(id);
                });
    }

    @Override
    public void beginMaintenance() {
        coordinator.beginMaintenance(() -> queue.activeCount() > 0);
    }

    @Override
    public void endMaintenance() {
        coordinator.endMaintenance();
    }
}
