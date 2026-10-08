package com.signdesk.backup;

import com.signdesk.common.ApiException;
import com.signdesk.common.Json;
import com.signdesk.engine.CurlParser;
import com.signdesk.engine.ResultRules;
import com.signdesk.engine.ProxySettings;
import com.signdesk.platform.CatalogService;
import com.signdesk.run.RunService;
import com.signdesk.run.SettingsService;
import com.signdesk.schedule.ScheduleSpec;
import com.signdesk.storage.Db;
import com.signdesk.storage.SecretStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Portable logical snapshot: one SQLite transaction; restore re-encrypts with the destination key.
 */
@Service
public class BackupService {
    private static final int ITERATIONS = 600000;
    private final Db db;
    private final CatalogService catalog;
    private final SecretStore secrets;
    private final RunService runs;
    private final CurlParser parser;
    private final Clock clock;
    private final TransactionTemplate tx;

    public record Request(
            String password, Boolean includeRequests, Map<String, Object> backup, Boolean replace) {
        public Request {
            includeRequests = Boolean.TRUE.equals(includeRequests);
            replace = Boolean.TRUE.equals(replace);
        }
    }

    public record RequestEntry(
            String id,
            String accountId,
            String name,
            boolean enabled,
            boolean authPaused,
            int sortOrder,
            ResultRules rules,
            String rawCurl) {}

    public record AccountEntry(
            String id, String platformId, String alias, boolean enabled, int sortOrder) {}

    public record PlatformEntry(
            String id, String name, String note, boolean enabled, int sortOrder) {}

    public record TemplateEntry(String id, String platformId, String name, ResultRules rules) {}

    public record ScheduleEntry(String platformId, ScheduleSpec spec) {}

    public record Completion(String requestId, String businessDate) {}

    public record Snapshot(
            int formatVersion,
            boolean includesRequests,
            SettingsService.Settings settings,
            List<PlatformEntry> platforms,
            List<AccountEntry> accounts,
            List<RequestEntry> requests,
            List<ScheduleEntry> schedules,
            List<Completion> completed,
            List<Completion> pending,
            List<TemplateEntry> templates) {
        public Snapshot {
            templates = templates == null ? List.of() : templates;
        }
    }

    public BackupService(
            Db db,
            CatalogService catalog,
            SecretStore secrets,
            RunService runs,
            CurlParser parser,
            Clock clock,
            PlatformTransactionManager manager) {
        this.db = db;
        this.catalog = catalog;
        this.secrets = secrets;
        this.runs = runs;
        this.parser = parser;
        this.clock = clock;
        tx = new TransactionTemplate(manager);
    }

    public Object export(Request request) {
        if (request.includeRequests()
                && (request.password() == null
                        || request.password().length() < 10
                        || request.password().length() > 200))
            throw new ApiException("完整备份需使用 10～200 字符密码");
        Snapshot snapshot =
                tx.execute(
                        status -> {
                            var platforms =
                                    db.rows("SELECT * FROM platforms ORDER BY rowid").stream()
                                            .map(
                                                    r ->
                                                            new PlatformEntry(
                                                                    Db.text(r, "id"),
                                                                    Db.text(r, "name"),
                                                                    Db.text(r, "note"),
                                                                    Db.flag(r, "enabled"),
                                                                    Db.integer(r, "sortOrder")))
                                            .toList();
                            var accounts =
                                    db.rows("SELECT * FROM accounts ORDER BY rowid").stream()
                                            .map(
                                                    r ->
                                                            new AccountEntry(
                                                                    Db.text(r, "id"),
                                                                    Db.text(r, "platformId"),
                                                                    Db.text(r, "alias"),
                                                                    Db.flag(r, "enabled"),
                                                                    Db.integer(r, "sortOrder")))
                                            .toList();
                            var requests =
                                    db.rows("SELECT * FROM requests ORDER BY rowid").stream()
                                            .map(
                                                    r ->
                                                            new RequestEntry(
                                                                    Db.text(r, "id"),
                                                                    Db.text(r, "accountId"),
                                                                    Db.text(r, "name"),
                                                                    Db.flag(r, "enabled"),
                                                                    Db.flag(r, "authPaused"),
                                                                    Db.integer(r, "sortOrder"),
                                                                    Json.read(
                                                                            Db.text(r, "rulesJson"),
                                                                            ResultRules.class),
                                                                    request.includeRequests()
                                                                            ? catalog.revision(
                                                                                            Db.text(
                                                                                                    r,
                                                                                                    "id"),
                                                                                            Db
                                                                                                    .integer(
                                                                                                            r,
                                                                                                            "currentRevision"))
                                                                                    .rawCurl()
                                                                            : null))
                                            .toList();
                            var schedules =
                                    db.rows("SELECT * FROM platform_schedules").stream()
                                            .map(
                                                    r ->
                                                            new ScheduleEntry(
                                                                    Db.text(r, "platformId"),
                                                                    new ScheduleSpec(
                                                                            Db.flag(r, "enabled"),
                                                                            Db.text(r, "frequency"),
                                                                            Json.read(
                                                                                    Db.text(
                                                                                            r,
                                                                                            "weekdaysJson"),
                                                                                    List.class),
                                                                            Json.read(
                                                                                    Db.text(
                                                                                            r,
                                                                                            "timesJson"),
                                                                                    List.class),
                                                                            Db.text(r, "timezone"),
                                                                            Db.integer(
                                                                                    r,
                                                                                    "intervalSeconds"),
                                                                            Db.integer(
                                                                                    r,
                                                                                    "catchupMinutes"),
                                                                            Db.flag(
                                                                                    r,
                                                                                    "skipCompletedDaily"),
                                                                            1)))
                                            .toList();
                            var completed =
                                    request.includeRequests()
                                            ? db
                                                    .rows(
                                                            "SELECT request_id,business_date FROM"
                                                                    + " daily_completions")
                                                    .stream()
                                                    .map(
                                                            r ->
                                                                    new Completion(
                                                                            Db.text(r, "requestId"),
                                                                            Db.text(
                                                                                    r,
                                                                                    "businessDate")))
                                                    .toList()
                                            : List.<Completion>of();
                            var pending =
                                    request.includeRequests()
                                            ? db
                                                    .rows(
                                                            "SELECT request_id,business_date FROM"
                                                                    + " request_day_states WHERE"
                                                                    + " unknown_pending=1")
                                                    .stream()
                                                    .map(
                                                            r ->
                                                                    new Completion(
                                                                            Db.text(r, "requestId"),
                                                                            Db.text(
                                                                                    r,
                                                                                    "businessDate")))
                                                    .toList()
                                            : List.<Completion>of();
                            var templates = db.rows("SELECT * FROM request_templates ORDER BY rowid")
                                    .stream()
                                    .map(r -> new TemplateEntry(
                                            Db.text(r, "id"), Db.text(r, "platformId"),
                                            Db.text(r, "name"),
                                            Json.read(Db.text(r, "rulesJson"), ResultRules.class)))
                                    .toList();
                            var settingsRow = db.one("SELECT * FROM settings WHERE id=1");
                            return new Snapshot(
                                    1,
                                    request.includeRequests(),
                                    new SettingsService.Settings(
                                            true,
                                            Db.integer(settingsRow, "concurrency"),
                                            Db.integer(settingsRow, "timeoutSeconds"),
                                            Db.integer(settingsRow, "retentionDays"),
                                            1,
                                            new ProxySettings(
                                                    Db.text(settingsRow, "proxyMode"),
                                                    Db.text(settingsRow, "proxyHost"),
                                                    Db.integer(settingsRow, "proxyPort"))),
                                    platforms,
                                    accounts,
                                    requests,
                                    schedules,
                                    completed,
                                    pending,
                                    templates);
                        });
        if (Json.write(snapshot).getBytes(StandardCharsets.UTF_8).length > 8388608)
            throw new ApiException("备份内容超过 8 MiB，不能生成可导入的文件");
        if (!request.includeRequests())
            return Map.of("format", "signdesk-config", "payload", snapshot);
        try {
            byte[] salt = random(16), nonce = random(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    derive(request.password(), salt),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD("signdesk-backup-v1".getBytes(StandardCharsets.UTF_8));
            return Map.of(
                    "format",
                    "signdesk-backup-v1",
                    "iterations",
                    ITERATIONS,
                    "salt",
                    b64(salt),
                    "nonce",
                    b64(nonce),
                    "ciphertext",
                    b64(cipher.doFinal(Json.write(snapshot).getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            throw new ApiException("无法创建加密备份");
        }
    }

    private Snapshot decode(Request request) {
        if (request.backup() == null) throw new ApiException("请提供备份文件");
        if (Json.write(request.backup()).length() > 12000000) throw new ApiException("备份超过 12 MiB");
        try {
            Map<String, Object> map = request.backup();
            String format = Objects.toString(map.get("format"), "");
            Snapshot snapshot;
            if (format.equals("signdesk-config")) {
                snapshot = Json.read(Json.write(map.get("payload")), Snapshot.class);
                if (snapshot.includesRequests()
                        || snapshot.requests().stream().anyMatch(r -> r.rawCurl() != null))
                    throw new ApiException("含完整请求的备份必须使用加密格式");
            } else if (format.equals("signdesk-backup-v1")) {
                if (request.password() == null
                        || request.password().length() > 200
                        || ((Number) map.get("iterations")).intValue() != ITERATIONS)
                    throw new IllegalArgumentException();
                byte[] salt = un64(map.get("salt")), nonce = un64(map.get("nonce"));
                if (salt.length != 16 || nonce.length != 12) throw new IllegalArgumentException();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        derive(request.password(), salt),
                        new GCMParameterSpec(128, nonce));
                cipher.updateAAD("signdesk-backup-v1".getBytes(StandardCharsets.UTF_8));
                snapshot =
                        Json.read(
                                new String(
                                        cipher.doFinal(un64(map.get("ciphertext"))),
                                        StandardCharsets.UTF_8),
                                Snapshot.class);
            } else throw new IllegalArgumentException();
            validate(snapshot);
            return snapshot;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("备份格式不正确、密码错误或内容已损坏");
        }
    }

    private void validate(Snapshot s) {
        if (s.settings() == null
                || s.settings().concurrency() < 1
                || s.settings().concurrency() > 8
                || s.settings().timeoutSeconds() < 1
                || s.settings().timeoutSeconds() > 120
                || s.settings().retentionDays() < 1
                || s.settings().retentionDays() > 365) throw new ApiException("备份设置范围不正确");
        s.settings().proxy().validated();
        if (s.formatVersion() != 1
                || s.platforms() == null
                || s.accounts() == null
                || s.requests() == null
                || s.schedules() == null
                || s.completed() == null
                || s.pending() == null
                || s.platforms().size() > 1000
                || s.accounts().size() > 10000
                || s.requests().size() > 10000
                || s.templates().size() > 10000) throw new ApiException("备份版本或对象数量不支持");
        Set<String> platforms = new HashSet<>(),
                accounts = new HashSet<>(),
                requests = new HashSet<>(),
                plans = new HashSet<>();
        for (var p : s.platforms()) {
            checkId(p.id());
            checkName(p.name(), 40);
            if (!platforms.add(p.id())) throw new IllegalArgumentException();
            if (p.note() != null && p.note().length() > 500) throw new IllegalArgumentException();
        }
        Set<String> templateIds = new HashSet<>();
        for (var template : s.templates()) {
            checkId(template.id());
            checkName(template.name(), 60);
            if (!platforms.contains(template.platformId()) || !templateIds.add(template.id()))
                throw new IllegalArgumentException();
            ResultRules.validate(template.rules());
        }
        for (var a : s.accounts()) {
            checkId(a.id());
            checkName(a.alias(), 40);
            if (!platforms.contains(a.platformId()) || !accounts.add(a.id()))
                throw new IllegalArgumentException();
        }
        for (var r : s.requests()) {
            checkId(r.id());
            checkName(r.name(), 60);
            if (!accounts.contains(r.accountId()) || !requests.add(r.id()))
                throw new IllegalArgumentException();
            ResultRules.validate(r.rules());
            if (s.includesRequests()) parser.parse(r.rawCurl());
            else if (r.rawCurl() != null) throw new IllegalArgumentException();
        }
        for (var plan : s.schedules()) {
            if (!platforms.contains(plan.platformId()) || !plans.add(plan.platformId()))
                throw new IllegalArgumentException();
            plan.spec().validated();
        }
        if (!plans.equals(platforms)) throw new IllegalArgumentException();
        for (var c :
                java.util.stream.Stream.concat(s.completed().stream(), s.pending().stream())
                        .toList()) {
            if (!requests.contains(c.requestId())) throw new IllegalArgumentException();
            java.time.LocalDate.parse(c.businessDate());
        }
    }

    public Object preview(Request r) {
        Snapshot s = decode(r);
        return Map.of(
                "platforms",
                s.platforms().size(),
                "accounts",
                s.accounts().size(),
                "requests",
                s.requests().size(),
                "templates",
                s.templates().size(),
                "includesRequests",
                s.includesRequests(),
                "mode",
                "replace",
                "message",
                "导入将替换所有现有平台、账号、请求、计划和执行记录；导入后保持全局定时暂停");
    }

    public void restore(Request request) {
        if (!request.replace()) throw new ApiException("需要明确确认替换现有配置");
        Snapshot s = decode(request);
        runs.beginMaintenance();
        try {
            tx.executeWithoutResult(
                    status -> {
                        db.update("DELETE FROM platforms");
                        for (var p : s.platforms())
                            db.update(
                                    "INSERT INTO platforms(id,name,note,enabled,sort_order)"
                                            + " VALUES(?,?,?,?,?)",
                                    p.id(),
                                    p.name(),
                                    p.note() == null ? "" : p.note(),
                                    p.enabled() ? 1 : 0,
                                    p.sortOrder());
                        for (var template : s.templates())
                            db.update(
                                    "INSERT INTO request_templates(id,platform_id,name,rules_json)"
                                            + " VALUES(?,?,?,?)",
                                    template.id(), template.platformId(), template.name(),
                                    Json.write(ResultRules.validate(template.rules())));
                        for (var a : s.accounts())
                            db.update(
                                    "INSERT INTO accounts(id,platform_id,alias,enabled,sort_order)"
                                            + " VALUES(?,?,?,?,?)",
                                    a.id(),
                                    a.platformId(),
                                    a.alias(),
                                    a.enabled() ? 1 : 0,
                                    a.sortOrder());
                        for (var r : s.requests()) {
                            var parsed = s.includesRequests() ? parser.parse(r.rawCurl()) : null;
                            db.update(
                                    "INSERT INTO"
                                        + " requests(id,account_id,name,enabled,rules_json,auth_paused,sort_order,safe_host,method)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                                    r.id(),
                                    r.accountId(),
                                    r.name(),
                                    s.includesRequests() && r.enabled() ? 1 : 0,
                                    Json.write(ResultRules.validate(r.rules())),
                                    !s.includesRequests() || r.authPaused() ? 1 : 0,
                                    r.sortOrder(),
                                    parsed == null
                                            ? ""
                                            : CurlParser.validateUrl(parsed.spec().rawUrl())
                                                    .getHost(),
                                    parsed == null ? "GET" : parsed.spec().method());
                            if (parsed != null)
                                db.update(
                                        "INSERT INTO request_revisions VALUES(?,1,?,?)",
                                        r.id(),
                                        secrets.encrypt(
                                                Json.write(
                                                        new CatalogService.StoredRequest(
                                                                r.rawCurl(), parsed.spec())),
                                                r.id() + ":1"),
                                        clock.instant().toString());
                        }
                        for (var plan : s.schedules()) {
                            var p = plan.spec().validated();
                            db.update(
                                    "INSERT INTO"
                                        + " platform_schedules(platform_id,enabled,frequency,weekdays_json,times_json,timezone,interval_seconds,catchup_minutes,skip_completed_daily,effective_from)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                                    plan.platformId(),
                                    p.enabled() ? 1 : 0,
                                    p.frequency(),
                                    Json.write(p.weekdays()),
                                    Json.write(p.times()),
                                    p.timezone(),
                                    p.intervalSeconds(),
                                    p.catchupMinutes(),
                                    p.skipCompletedDaily() ? 1 : 0,
                                    clock.instant().toString());
                        }
                        for (var c : s.completed())
                            db.update(
                                    "INSERT OR IGNORE INTO daily_completions"
                                            + " VALUES(?,?,'restored')",
                                    c.requestId(),
                                    c.businessDate());
                        for (var c : s.pending())
                            db.update(
                                    "INSERT OR IGNORE INTO request_day_states VALUES(?,?,1)",
                                    c.requestId(),
                                    c.businessDate());
                        db.update(
                                "UPDATE settings SET"
                                    + " paused=1,concurrency=?,timeout_seconds=?,retention_days=?,"
                                    + " proxy_mode=?,proxy_host=?,proxy_port=?,version=version+1"
                                    + " WHERE id=1",
                                s.settings().concurrency(),
                                s.settings().timeoutSeconds(),
                                s.settings().retentionDays(),
                                s.settings().proxy().mode(),
                                s.settings().proxy().host(),
                                s.settings().proxy().port());
                    });
        } finally {
            runs.endMaintenance();
        }
    }

    private static void checkId(String id) {
        if (id == null) throw new IllegalArgumentException();
        if (id.matches("[a-f0-9]{32}")) return;
        if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0)
            throw new IllegalArgumentException();
    }

    private static void checkName(String name, int max) {
        if (name == null || name.isBlank() || name.length() > max)
            throw new IllegalArgumentException();
    }

    private static SecretKeySpec derive(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try {
            return new SecretKeySpec(
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                            .generateSecret(spec)
                            .getEncoded(),
                    "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] random(int n) {
        byte[] out = new byte[n];
        new SecureRandom().nextBytes(out);
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] un64(Object value) {
        return Base64.getDecoder().decode(value.toString());
    }
}
