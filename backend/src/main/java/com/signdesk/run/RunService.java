package com.signdesk.run;

import cn.hutool.core.util.IdUtil;

import com.signdesk.common.ApiException;
import com.signdesk.common.Json;
import com.signdesk.engine.HutoolRequestExecutor;
import com.signdesk.engine.ResultRules;
import com.signdesk.platform.CatalogService;
import com.signdesk.schedule.ScheduleService;
import com.signdesk.schedule.ScheduleSpec;
import com.signdesk.storage.Db;

import jakarta.annotation.PreDestroy;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class RunService {
    private final Db db;
    private final SettingsService settings;
    private final ScheduleService schedules;
    private final CatalogService catalog;
    private final HutoolRequestExecutor http;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Set<String> activePlatforms = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers =
            Executors.newFixedThreadPool(
                    8,
                    r -> {
                        Thread t = new Thread(r, "platform-worker");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile boolean ready = false, closed = false, maintenance = false;

    public record ManualRun(String scope, String id, boolean force, String key) {}

    public RunService(
            Db db,
            SettingsService settings,
            ScheduleService schedules,
            CatalogService catalog,
            HutoolRequestExecutor http,
            Clock clock,
            PlatformTransactionManager manager) {
        this.db = db;
        this.settings = settings;
        this.schedules = schedules;
        this.catalog = catalog;
        this.http = http;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        transactions.executeWithoutResult(
                tx -> {
                    db.update(
                            "INSERT INTO"
                                + " request_day_states(request_id,business_date,unknown_pending)"
                                + " SELECT i.request_id,b.business_date,1 FROM run_items i JOIN"
                                + " run_batches b ON b.id=i.batch_id WHERE i.status='running' ON"
                                + " CONFLICT(request_id,business_date) DO UPDATE SET"
                                + " unknown_pending=1");
                    db.update(
                            "UPDATE run_items SET"
                                + " status='unknown',safe_summary='服务上次中断，发送结果待确认',finished_at=?"
                                + " WHERE status='running'",
                            clock.instant().toString());
                    db.update("UPDATE run_batches SET status='queued' WHERE status='running'");
                });
        ready = true;
    }

    public synchronized List<String> manual(ManualRun request) {
        if (maintenance) throw new ApiException(409, "正在维护配置，请稍后再执行");
        if (request.key() == null || !request.key().matches("[a-zA-Z0-9-]{8,100}"))
            throw new ApiException("执行请求需要有效的幂等 key");
        if (!List.of("all", "platform", "request").contains(request.scope()))
            throw new ApiException("执行范围不正确");
        List<String> platformIds;
        if (request.scope().equals("all"))
            platformIds =
                    db.rows("SELECT id FROM platforms WHERE enabled=1 ORDER BY rowid").stream()
                            .map(r -> Db.text(r, "id"))
                            .toList();
        else if (request.scope().equals("platform")) platformIds = List.of(request.id());
        else
            platformIds =
                    List.of(
                            Db.text(
                                    db.one(
                                            "SELECT a.platform_id FROM requests r JOIN accounts a"
                                                    + " ON a.id=r.account_id WHERE r.id=?",
                                            request.id()),
                                    "platformId"));
        List<String> result =
                transactions.execute(
                        tx -> {
                            List<String> ids = new ArrayList<>();
                            for (String platformId : platformIds) {
                                String manualKey = request.key() + ":" + platformId;
                                var existing =
                                        db.rows(
                                                "SELECT id FROM run_batches WHERE manual_key=?",
                                                manualKey);
                                if (!existing.isEmpty()) {
                                    ids.add(Db.text(existing.getFirst(), "id"));
                                    continue;
                                }
                                var platform =
                                        db.one(
                                                "SELECT enabled FROM platforms WHERE id=?",
                                                platformId);
                                if (!Db.flag(platform, "enabled"))
                                    throw new ApiException(409, "平台已停用，请先启用");
                                ScheduleSpec schedule = schedules.get(platformId);
                                var eligible =
                                        eligible(
                                                platformId,
                                                request.scope().equals("request")
                                                        ? request.id()
                                                        : null);
                                if (eligible.isEmpty()) continue;
                                ids.add(
                                        enqueue(
                                                platformId,
                                                schedule,
                                                null,
                                                manualKey,
                                                request.force(),
                                                eligible));
                            }
                            return ids;
                        });
        if (result.isEmpty()) throw new ApiException(409, "没有可执行的请求，或请求已在队列中");
        return result;
    }

    private List<Map<String, Object>> eligible(String platformId, String requestId) {
        String filter = requestId == null ? "" : " AND r.id=?";
        String sql =
                "SELECT r.* FROM requests r JOIN accounts a ON a.id=r.account_id WHERE"
                        + " a.platform_id=? AND a.enabled=1 AND r.enabled=1"
                        + filter
                        + " AND NOT EXISTS(SELECT 1 FROM run_items i WHERE i.request_id=r.id AND"
                        + " i.status IN('queued','running')) ORDER BY"
                        + " a.sort_order,a.rowid,r.sort_order,r.rowid";
        return requestId == null ? db.rows(sql, platformId) : db.rows(sql, platformId, requestId);
    }

    public synchronized void automatic(
            String platformId, ScheduleSpec schedule, Instant scheduledAt) {
        if (maintenance || closed || !ready) return;
        transactions.executeWithoutResult(
                tx -> {
                    if (db.count(
                                    "SELECT COUNT(*) FROM run_batches WHERE platform_id=? AND"
                                            + " scheduled_at=? AND source='auto'",
                                    platformId,
                                    scheduledAt.toString())
                            == 0)
                        enqueue(
                                platformId,
                                schedule,
                                scheduledAt,
                                null,
                                false,
                                eligible(platformId, null));
                });
    }

    private String enqueue(
            String platformId,
            ScheduleSpec s,
            Instant at,
            String manualKey,
            boolean force,
            List<Map<String, Object>> eligible) {
        if (db.count("SELECT COUNT(*) FROM run_items WHERE status IN('queued','running')")
                        + eligible.size()
                > 10000) throw new ApiException(409, "队列已达上限，请等待完成");
        String batch = IdUtil.fastSimpleUUID();
        Instant now = clock.instant();
        String date =
                (at == null ? now : at).atZone(ZoneId.of(s.timezone())).toLocalDate().toString();
        String expires =
                at == null
                        ? null
                        : at.plusSeconds(Math.max(30, s.catchupMinutes() * 60L)).toString();
        db.update(
                "INSERT INTO"
                    + " run_batches(id,platform_id,schedule_revision,scheduled_at,business_date,timezone,source,manual_key,force,skip_completed_daily,interval_seconds,expires_at,status,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                batch,
                platformId,
                s.revision(),
                at == null ? null : at.toString(),
                date,
                s.timezone(),
                at == null ? "manual" : "auto",
                manualKey,
                force ? 1 : 0,
                s.skipCompletedDaily() ? 1 : 0,
                s.intervalSeconds(),
                expires,
                eligible.isEmpty() ? "completed" : "queued",
                now.toString());
        int order = 0;
        for (var r : eligible)
            db.update(
                    "INSERT INTO"
                        + " run_items(id,batch_id,request_id,request_revision,rules_json,ordinal)"
                        + " VALUES(?,?,?,?,?,?)",
                    IdUtil.fastSimpleUUID(),
                    batch,
                    r.get("id"),
                    r.get("currentRevision"),
                    r.get("rulesJson"),
                    order++);
        return batch;
    }

    @Scheduled(fixedDelay = 500)
    public synchronized void dispatch() {
        if (!ready || closed || maintenance) return;
        int cap = settings.get().concurrency();
        for (var batch :
                db.rows(
                        "SELECT * FROM run_batches WHERE status='queued' ORDER BY created_at,rowid"
                                + " LIMIT 100")) {
            if (activePlatforms.size() >= cap) break;
            String platformId = Db.text(batch, "platformId"), id = Db.text(batch, "id");
            if (!activePlatforms.add(platformId)) continue;
            if (db.update(
                            "UPDATE run_batches SET status='running' WHERE id=? AND"
                                    + " status='queued'",
                            id)
                    != 1) {
                activePlatforms.remove(platformId);
                continue;
            }
            workers.submit(
                    () -> {
                        try {
                            work(batch);
                        } catch (Exception e) {
                            org.slf4j.LoggerFactory.getLogger(RunService.class)
                                    .error(
                                            "Queue operation failed ({})",
                                            e.getClass().getSimpleName());
                        } finally {
                            // Failed persistence does NOT retry the HTTP call: running item becomes
                            // unknown on the next dispatch.
                            try {
                                transactions.executeWithoutResult(
                                        tx -> {
                                            for (var item :
                                                    db.rows(
                                                            "SELECT request_id FROM run_items WHERE"
                                                                    + " batch_id=? AND"
                                                                    + " status='running'",
                                                            id))
                                                db.update(
                                                        "INSERT INTO request_day_states"
                                                            + " VALUES(?,?,1) ON"
                                                            + " CONFLICT(request_id,business_date)"
                                                            + " DO UPDATE SET unknown_pending=1",
                                                        item.get("requestId"),
                                                        batch.get("businessDate"));
                                            db.update(
                                                    "UPDATE run_items SET"
                                                        + " status='unknown',safe_summary='执行过程异常，结果待确认',finished_at=?"
                                                        + " WHERE batch_id=? AND status='running'",
                                                    clock.instant().toString(),
                                                    id);
                                            long pending =
                                                    db.count(
                                                            "SELECT COUNT(*) FROM run_items WHERE"
                                                                + " batch_id=? AND status='queued'",
                                                            id);
                                            db.update(
                                                    "UPDATE run_batches SET status=? WHERE id=?",
                                                    pending > 0 ? "queued" : "completed",
                                                    id);
                                        });
                            } catch (Exception ignored) {
                                ready = false;
                            }
                            activePlatforms.remove(platformId);
                        }
                    });
        }
    }

    private void work(Map<String, Object> batch) throws Exception {
        String batchId = Db.text(batch, "id");
        boolean sent = false;
        batch.put(
                "windowExpired",
                Db.text(batch, "source").equals("auto")
                        && clock.instant().isAfter(Instant.parse(Db.text(batch, "expiresAt"))));
        for (var item :
                db.rows(
                        "SELECT * FROM run_items WHERE batch_id=? AND status='queued' ORDER BY"
                                + " ordinal",
                        batchId)) {
            if (closed) break;
            if (sent) {
                for (int i = 0; i < Db.integer(batch, "intervalSeconds") && !closed; i++) {
                    if (Db.flag(
                            db.one("SELECT cancel_requested FROM run_batches WHERE id=?", batchId),
                            "cancelRequested")) break;
                    Thread.sleep(1000);
                }
            }
            String itemId = Db.text(item, "id"), requestId = Db.text(item, "requestId");
            String reason =
                    transactions.execute(
                            tx -> {
                                String skip = gate(batch, item);
                                if (skip != null)
                                    db.update(
                                            "UPDATE run_items SET"
                                                    + " status=?,safe_summary=?,finished_at=? WHERE"
                                                    + " id=? AND status='queued'",
                                            skip.startsWith("取消") ? "cancelled" : "skipped",
                                            skip,
                                            clock.instant().toString(),
                                            itemId);
                                else if (db.update(
                                                "UPDATE run_items SET status='running',started_at=?"
                                                        + " WHERE id=? AND status='queued'",
                                                clock.instant().toString(),
                                                itemId)
                                        != 1) return "已被领取";
                                return skip;
                            });
            if (reason != null) continue;
            HutoolRequestExecutor.Result result;
            try {
                result =
                        http.execute(
                                catalog.revision(requestId, Db.integer(item, "requestRevision"))
                                        .spec(),
                                Json.read(Db.text(item, "rulesJson"), ResultRules.class),
                                settings.get().timeoutSeconds());
            } catch (Exception e) {
                result = new HutoolRequestExecutor.Result("failed", null, 0, "请求快照无法解密，请检查原密钥");
            }
            sent = true;
            persistResult(
                    itemId,
                    requestId,
                    Db.integer(item, "requestRevision"),
                    Db.text(batch, "businessDate"),
                    result);
        }
    }

    private String gate(Map<String, Object> batch, Map<String, Object> item) {
        var fresh =
                db.one(
                        "SELECT b.cancel_requested,p.enabled AS platform_enabled,a.enabled AS"
                            + " account_enabled,r.enabled,r.auth_paused FROM run_batches b JOIN"
                            + " platforms p ON p.id=b.platform_id JOIN requests r ON r.id=? JOIN"
                            + " accounts a ON a.id=r.account_id WHERE b.id=?",
                        item.get("requestId"),
                        batch.get("id"));
        if (Db.flag(fresh, "cancelRequested")) return "取消剩余队列";
        if (!Db.flag(fresh, "platformEnabled")
                || !Db.flag(fresh, "accountEnabled")
                || !Db.flag(fresh, "enabled")) return "平台、账号或请求已停用";
        if (Db.flag(fresh, "authPaused")) return "凭证过期，需更新 cURL";
        if (Db.text(batch, "source").equals("auto")) {
            Instant now = clock.instant();
            if (!Db.text(batch, "businessDate")
                            .equals(
                                    now.atZone(ZoneId.of(Db.text(batch, "timezone")))
                                            .toLocalDate()
                                            .toString())
                    || Boolean.TRUE.equals(batch.get("windowExpired"))) return "补执行窗口已过期";
        }
        if (!Db.flag(batch, "force")) {
            if (Db.flag(batch, "skipCompletedDaily")
                    && db.count(
                                    "SELECT COUNT(*) FROM daily_completions WHERE request_id=? AND"
                                            + " business_date=?",
                                    item.get("requestId"),
                                    batch.get("businessDate"))
                            > 0) return "今日已完成";
            if (db.count(
                            "SELECT COUNT(*) FROM request_day_states WHERE request_id=? AND"
                                    + " business_date=? AND unknown_pending=1",
                            item.get("requestId"),
                            batch.get("businessDate"))
                    > 0) return "本周期有待确认结果，请先核对后主动重新执行";
        }
        return null;
    }

    private void persistResult(
            String itemId,
            String requestId,
            int revision,
            String date,
            HutoolRequestExecutor.Result result) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                transactions.executeWithoutResult(
                        tx -> {
                            db.update(
                                    "UPDATE run_items SET"
                                        + " status=?,http_status=?,duration_ms=?,safe_summary=?,finished_at=?"
                                        + " WHERE id=? AND status='running'",
                                    result.status(),
                                    result.httpStatus(),
                                    result.durationMs(),
                                    result.summary(),
                                    clock.instant().toString(),
                                    itemId);
                            if (List.of("success", "already_done").contains(result.status()))
                                db.update(
                                        "INSERT INTO daily_completions VALUES(?,?,?) ON"
                                            + " CONFLICT(request_id,business_date) DO UPDATE SET"
                                            + " completed_run_id=excluded.completed_run_id",
                                        requestId,
                                        date,
                                        itemId);
                            if (result.status().equals("expired"))
                                db.update(
                                        "UPDATE requests SET auth_paused=1,version=version+1 WHERE"
                                                + " id=? AND current_revision=?",
                                        requestId,
                                        revision);
                            db.update(
                                    "INSERT INTO request_day_states VALUES(?,?,?) ON"
                                            + " CONFLICT(request_id,business_date) DO UPDATE SET"
                                            + " unknown_pending=excluded.unknown_pending",
                                    requestId,
                                    date,
                                    result.status().equals("unknown") ? 1 : 0);
                        });
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    public void cancel(String id) {
        transactions.executeWithoutResult(
                tx -> {
                    if (db.update("UPDATE run_batches SET cancel_requested=1 WHERE id=?", id) != 1)
                        throw new ApiException(404, "批次不存在");
                    db.update(
                            "UPDATE run_items SET"
                                + " status='cancelled',safe_summary='取消剩余队列',finished_at=? WHERE"
                                + " batch_id=? AND status='queued'",
                            clock.instant().toString(),
                            id);
                    db.update(
                            "UPDATE run_batches SET status='completed' WHERE id=? AND"
                                    + " status='queued'",
                            id);
                });
    }

    public Map<String, Object> batch(String id) {
        var r = db.one("SELECT * FROM run_batches WHERE id=?", id);
        r.put(
                "items",
                db.rows(
                        "SELECT"
                            + " i.id,i.request_id,i.request_revision,i.status,i.safe_summary,r.name,a.alias"
                            + " FROM run_items i JOIN requests r ON r.id=i.request_id JOIN accounts"
                            + " a ON a.id=r.account_id WHERE batch_id=? ORDER BY ordinal",
                        id));
        return r;
    }

    public List<Map<String, Object>> active() {
        return db.rows(
                "SELECT b.*,p.name,(SELECT COUNT(*) FROM run_items i WHERE i.batch_id=b.id) AS"
                    + " total,(SELECT COUNT(*) FROM run_items i WHERE i.batch_id=b.id AND i.status"
                    + " NOT IN('queued','running')) AS done FROM run_batches b JOIN platforms p ON"
                    + " p.id=b.platform_id WHERE b.status IN('queued','running') ORDER BY"
                    + " b.created_at");
    }

    public Map<String, Object> logs(
            String platformId, String status, String source, int page, int size) {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(100, size));
        String where = " WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (platformId != null && !platformId.isBlank()) {
            where += " AND b.platform_id=?";
            args.add(platformId);
        }
        if (status != null && !status.isBlank()) {
            where += " AND i.status=?";
            args.add(status);
        }
        if (source != null && !source.isBlank()) {
            where += " AND b.source=?";
            args.add(source);
        }
        String from =
                " FROM run_items i JOIN run_batches b ON b.id=i.batch_id JOIN requests r ON"
                    + " r.id=i.request_id JOIN accounts a ON a.id=r.account_id JOIN platforms p ON"
                    + " p.id=b.platform_id";
        long total = db.count("SELECT COUNT(*)" + from + where, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows =
                db.rows(
                        "SELECT"
                            + " i.id,i.batch_id,i.request_id,i.request_revision,i.status,i.http_status,i.duration_ms,i.safe_summary,i.started_at,i.finished_at,b.created_at,b.business_date,b.source,p.name"
                            + " AS platform_name,a.alias,r.name AS request_name"
                                + from
                                + where
                                + " ORDER BY b.created_at DESC,i.rowid DESC LIMIT ? OFFSET ?",
                        args.toArray());
        return Map.of("items", rows, "total", total, "page", page, "size", size);
    }

    public synchronized void beginMaintenance() {
        if (maintenance
                || !activePlatforms.isEmpty()
                || db.count("SELECT COUNT(*) FROM run_items WHERE status IN('queued','running')")
                        > 0) throw new ApiException(409, "请等待队列空闲后再导入或恢复");
        maintenance = true;
    }

    public synchronized void endMaintenance() {
        maintenance = false;
    }

    public boolean ready() {
        return ready && !closed;
    }

    @PreDestroy
    public void close() throws InterruptedException {
        closed = true;
        workers.shutdown();
        if (!workers.awaitTermination(25, TimeUnit.SECONDS)) workers.shutdownNow();
    }
}
