package com.signdesk.run;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.backup.BackupService;
import com.signdesk.common.ApiException;
import com.signdesk.common.Json;
import com.signdesk.engine.CurlParser;
import com.signdesk.engine.ResultRules;
import com.signdesk.engine.ProxySettings;
import com.signdesk.platform.CatalogService;
import com.signdesk.platform.PlatformMapper;
import com.signdesk.schedule.ScheduleService;
import com.signdesk.schedule.ScheduleSpec;
import com.signdesk.storage.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageQueueIntegrationTest {
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("signdesk-java-test-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signdesk.data-dir", () -> DIR.resolve("data").toString());
        registry.add("signdesk.key-file", () -> DIR.resolve("secrets/master.key").toString());
        registry.add("signdesk.allowed-hosts", () -> "127.0.0.1");
    }

    @Autowired CatalogService catalog;
    @Autowired RunService runs;
    @Autowired Db db;
    @Autowired SettingsService settings;
    @Autowired ScheduleService schedules;
    @Autowired BackupService backups;
    @Autowired SecretStore secrets;
    @Autowired InstanceLock lock;
    @Autowired DatabaseMigrator migrator;
    @Autowired PlatformMapper mapper;
    @Autowired CurlParser parser;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    HttpServer server;
    String url;
    AtomicInteger hits = new AtomicInteger();
    List<String> bodies = new CopyOnWriteArrayList<>();
    CountDownLatch entered, release;

    @BeforeAll
    void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            return t;
                        }));
        server.createContext(
                "/ok",
                e -> {
                    hits.incrementAndGet();
                    bodies.add(
                            new String(
                                    e.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    byte[] b = "{\"code\":0}".getBytes();
                    e.sendResponseHeaders(200, b.length);
                    e.getResponseBody().write(b);
                    e.close();
                });
        server.createContext(
                "/expired",
                e -> {
                    hits.incrementAndGet();
                    e.sendResponseHeaders(401, -1);
                    e.close();
                });
        server.createContext(
                "/hold-expired",
                e -> {
                    hits.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                        e.sendResponseHeaders(401, -1);
                    } catch (Exception ignored) {
                    }
                    e.close();
                });
        server.createContext(
                "/unmatched",
                e -> {
                    hits.incrementAndGet();
                    byte[] b = "{\"code\":99}".getBytes();
                    e.sendResponseHeaders(200, b.length);
                    e.getResponseBody().write(b);
                    e.close();
                });
        server.createContext(
                "/hold",
                e -> {
                    hits.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                        byte[] b = "{\"code\":0}".getBytes();
                        e.sendResponseHeaders(200, b.length);
                        e.getResponseBody().write(b);
                    } catch (Exception ignored) {
                    }
                    e.close();
                });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    void stopFixture() throws Exception {
        server.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        await(() -> runs.active().isEmpty());
        db.update("DELETE FROM platforms");
        var s = settings.get();
        settings.save(new SettingsService.Settings(true, 2, 20, 30, s.version()));
        hits.set(0);
        bodies.clear();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    String platform, account, request;

    void setup(String endpoint) {
        platform = catalog.addPlatform("测试平台", "", true);
        account = catalog.addAccount(platform, "账号 A", true);
        request =
                catalog.addRequest(
                        account,
                        "每日签到",
                        "curl '"
                                + url
                                + endpoint
                                + "' -H 'Cookie: test-only-credential=alpha' --data-raw"
                                + " 'first-body'",
                        ResultRules.defaults(),
                        true);
        var s = schedules.get(platform);
        schedules.save(
                platform,
                new ScheduleSpec(
                        false,
                        "daily",
                        s.weekdays(),
                        s.times(),
                        s.timezone(),
                        0,
                        120,
                        true,
                        s.revision()));
    }

    String manual(boolean force) {
        return runs.manual(
                        new RunService.ManualRun(
                                "request", request, force, UUID.randomUUID().toString()))
                .getFirst();
    }

    void complete(String batch) throws Exception {
        await(() -> Db.text(runs.batch(batch), "status").equals("completed"));
    }

    String status(String batch) {
        return Db.text(
                ((List<Map<String, Object>>) runs.batch(batch).get("items")).getFirst(), "status");
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(30);
        assertTrue(condition.getAsBoolean(), "operation did not finish");
    }

    @Test
    void templatesArePlatformScopedValidatedVersionedAndCascadeWithTheirPlatform() {
        platform = catalog.addPlatform("模板平台", "", true);
        String other = catalog.addPlatform("另一个平台", "", true);
        var rules = new ResultRules(null, new ResultRules.Match("done", true, null), null, null);
        String id = catalog.addTemplate(platform, "  查询积分  ", rules);
        assertTrue(id.matches("[1-9][0-9]{0,18}"));
        assertTrue(Long.parseLong(id) > 0);
        assertEquals(List.of(), catalog.templates(other));
        var stored = catalog.templates(platform).getFirst();
        assertEquals("查询积分", stored.get("name"));
        assertEquals(rules, stored.get("rules"));
        assertFalse(stored.containsKey("rulesJson"));
        assertEquals(1, Db.integer(stored, "version"));
        catalog.updateTemplate(platform, id, "查询结果", ResultRules.defaults(), 1);
        assertEquals(2, Db.integer(catalog.templates(platform).getFirst(), "version"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> catalog.updateTemplate(platform, id, "冲突", rules, 1)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> catalog.updateTemplate(other, id, "错误平台", rules, 2)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> catalog.deleteTemplate(other, id)).status());
        assertThrows(ApiException.class, () -> catalog.addTemplate(platform, " ", rules));
        assertThrows(ApiException.class, () -> catalog.addTemplate(platform, "名".repeat(61), rules));
        var invalid = new ResultRules(new ResultRules.Match("bad[*]", 0, null), null, null, null);
        assertThrows(ApiException.class, () -> catalog.addTemplate(platform, "错误规则", invalid));
        assertThrows(ApiException.class, () -> catalog.updateTemplate(platform, id, "错误规则", invalid, 2));
        assertEquals("查询结果", catalog.templates(platform).getFirst().get("name"));
        String second = catalog.addTemplate(platform, "签到", null);
        catalog.deleteTemplate(platform, second);
        assertEquals(1, catalog.templates(platform).size());
        catalog.delete("platforms", platform);
        assertEquals(0, db.count("SELECT COUNT(*) FROM request_templates"));
    }

    @Test
    void templateChangesDoNotChangeRequestsOrQueuedRuleSnapshots() throws Exception {
        setup("/hold");
        String template = catalog.addTemplate(platform, "领取奖励", ResultRules.defaults());
        var copied = (ResultRules) catalog.templates(platform).getFirst().get("rules");
        String reward = catalog.addRequest(account, "领取奖励",
                "curl '" + url + "/ok' --data-raw 'template-body'", copied, true);
        String batch = runs.manual(new RunService.ManualRun(
                "platform", platform, false, "template-snapshot-fixture")).getFirst();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            var changed = new ResultRules(new ResultRules.Match("code", 99, null), null, null, null);
            catalog.updateTemplate(platform, template, "新规则", changed, 1);
            catalog.deleteTemplate(platform, template);
            assertEquals(copied, Json.read(Db.text(db.one(
                    "SELECT rules_json FROM requests WHERE id=?", reward), "rulesJson"), ResultRules.class));
            assertEquals(copied, Json.read(Db.text(db.one(
                    "SELECT rules_json FROM run_items WHERE request_id=? AND batch_id=?", reward, batch),
                    "rulesJson"), ResultRules.class));
            catalog.updateRequest(reward, "领取奖励", true, changed, 1);
        } finally {
            release.countDown();
        }
        complete(batch);
        var items = (List<Map<String, Object>>) runs.batch(batch).get("items");
        assertEquals(List.of("success", "success"), items.stream().map(i -> Db.text(i, "status")).toList());
        assertEquals(List.of("template-body"), bodies);
        assertEquals(2, hits.get());
    }

    @Test
    void snowflakeIdsAreStringsAcrossCatalogAndExecution() throws Exception {
        setup("/ok");
        String batch = manual(false);
        complete(batch);
        var batchRow = runs.batch(batch);
        var item = ((List<Map<String, Object>>) batchRow.get("items")).getFirst();
        String itemId = Db.text(item, "id");
        var ids = List.of(platform, account, request, batch, itemId);
        assertEquals(ids.size(), new HashSet<>(ids).size());
        for (String id : ids) {
            assertTrue(id.matches("[1-9][0-9]{0,18}"), "ID must be a decimal snowflake string");
            assertTrue(Long.parseLong(id) > 9_007_199_254_740_991L);
        }
        assertEquals(platform, mapper.selectById(platform).id);
        assertEquals(platform, db.one("SELECT platform_id FROM accounts WHERE id=?", account).get("platformId"));
        assertEquals(account, db.one("SELECT account_id FROM requests WHERE id=?", request).get("accountId"));
        assertEquals(platform, batchRow.get("platformId"));
        assertEquals(batch, db.one("SELECT batch_id FROM run_items WHERE id=?", itemId).get("batchId"));
        assertEquals(request, item.get("requestId"));
        var json = Json.tree(Json.write(batchRow));
        assertTrue(json.path("id").isString());
        assertEquals(batch, json.path("id").asString());
        assertTrue(json.path("items").get(0).path("id").isString());
        assertEquals(itemId, json.path("items").get(0).path("id").asString());
        assertEquals("success", status(batch));
        assertEquals(1, hits.get());
    }

    @Test
    void queueUsesPersistedProxyWithoutRestartAndRejectsInvalidSettings() throws Exception {
        setup("/ok");
        catalog.replaceRequest(request, "curl 'http://127.0.0.1:1/ok' --data-raw 'proxy-body'", 1);
        var previous = settings.get();
        var proxy = new ProxySettings("http", "127.0.0.1", server.getAddress().getPort());
        settings.save(new SettingsService.Settings(true, 2, 3, 30, previous.version(), proxy));
        assertEquals(proxy, new SettingsService(db).get().proxy());
        var saved = settings.get();
        assertThrows(ApiException.class, () -> settings.save(new SettingsService.Settings(
                true, 2, 3, 30, saved.version(), new ProxySettings("http", "http://user:secret@proxy", 80))));
        assertThrows(ApiException.class, () -> settings.save(new SettingsService.Settings(
                true, 2, 3, 30, saved.version(), new ProxySettings("http", "localhost", 0))));
        assertThrows(ApiException.class, () -> settings.save(new SettingsService.Settings(
                true, 2, 3, 30, saved.version(), new ProxySettings("other", "localhost", 80))));
        assertEquals(saved, settings.get(), "invalid saves must not change stored settings");
        String batch = manual(false);
        complete(batch);
        assertEquals("success", status(batch));
        assertEquals(List.of("proxy-body"), bodies);
        assertEquals(1, hits.get());
        assertThrows(ApiException.class, () -> settings.save(new SettingsService.Settings(
                true, 2, 3, 30, previous.version(), ProxySettings.system())));
        assertEquals(proxy, settings.get().proxy(), "stale saves must not change proxy settings");
    }

    @Test
    void legacyBackupWithoutProxyFieldsStillRestores() {
        setup("/ok");
        catalog.addTemplate(platform, "旧备份中没有的模板", ResultRules.defaults());
        var exported = (Map<String, Object>) backups.export(new BackupService.Request(null, false, null, false));
        var backup = Json.map(Json.write(exported));
        var payload = (Map<String, Object>) backup.get("payload");
        ((Map<String, Object>) payload.get("settings")).remove("proxy");
        payload.remove("templates");
        var previous = settings.get();
        settings.save(new SettingsService.Settings(true, 2, 20, 30, previous.version(),
                new ProxySettings("http", "127.0.0.1", 12345)));
        backups.restore(new BackupService.Request(null, false, backup, true));
        assertEquals(ProxySettings.system(), settings.get().proxy());
        assertTrue(catalog.templates(platform).isEmpty());
        assertTrue(settings.get().paused());
    }

    @Test
    void actualSqliteSecretsAreEncryptedAndVersionIsFrozen() throws Exception {
        setup("/ok");
        String batch = manual(false);
        catalog.replaceRequest(request, "curl '" + url + "/ok' --data-raw 'second-body'", 1);
        complete(batch);
        assertEquals("success", status(batch));
        assertEquals(List.of("first-body"), bodies);
        assertEquals(
                1,
                Db.integer(
                        ((List<Map<String, Object>>) runs.batch(batch).get("items")).getFirst(),
                        "requestRevision"));
        assertEquals(
                2,
                Db.integer(
                        db.one("SELECT current_revision FROM requests WHERE id=?", request),
                        "currentRevision"));
        String ciphertext =
                Db.text(
                        db.one(
                                "SELECT ciphertext FROM request_revisions WHERE request_id=? AND"
                                        + " revision=1",
                                request),
                        "ciphertext");
        assertFalse(ciphertext.contains("test-only-credential"));
        assertThrows(
                IllegalStateException.class, () -> secrets.decrypt(ciphertext, request + ":2"));
    }

    @Test
    void manualIdempotencyAndDailyMarkersSurviveLogCleanup() throws Exception {
        setup("/ok");
        var command =
                new RunService.ManualRun("request", request, false, UUID.randomUUID().toString());
        var ids = runs.manual(command);
        assertEquals(ids, runs.manual(command));
        complete(ids.getFirst());
        assertEquals(1, hits.get());
        db.update("DELETE FROM run_items");
        String second = manual(false);
        complete(second);
        assertEquals("skipped", status(second));
        assertEquals(1, hits.get());
        String forced = manual(true);
        complete(forced);
        assertEquals("success", status(forced));
        assertEquals(2, hits.get());
    }

    @Test
    void outdatedExpiredRevisionDoesNotPauseNewCredentials() throws Exception {
        setup("/hold-expired");
        String batch = manual(false);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        catalog.replaceRequest(request, "curl '" + url + "/ok'", 1);
        release.countDown();
        complete(batch);
        assertEquals("expired", status(batch));
        assertFalse(
                Db.flag(
                        db.one("SELECT auth_paused FROM requests WHERE id=?", request),
                        "authPaused"));
        String next = manual(false);
        complete(next);
        assertEquals("success", status(next));
    }

    @Test
    void unknownPreventsAutomaticReplayAndExpiredNeedsNewCurl() throws Exception {
        setup("/unmatched");
        String first = manual(false);
        complete(first);
        assertEquals("unknown", status(first));
        String second = manual(false);
        complete(second);
        assertEquals("skipped", status(second));
        assertEquals(1, hits.get());
        var row = db.one("SELECT version FROM requests WHERE id=?", request);
        catalog.replaceRequest(request, "curl '" + url + "/expired'", Db.integer(row, "version"));
        String force = manual(true);
        complete(force);
        assertEquals("expired", status(force));
        assertTrue(
                Db.flag(
                        db.one("SELECT auth_paused FROM requests WHERE id=?", request),
                        "authPaused"));
        row = db.one("SELECT version FROM requests WHERE id=?", request);
        catalog.replaceRequest(request, "curl '" + url + "/ok'", Db.integer(row, "version"));
        assertFalse(
                Db.flag(
                        db.one("SELECT auth_paused FROM requests WHERE id=?", request),
                        "authPaused"));
        String last = manual(true);
        complete(last);
        assertEquals("success", status(last));
    }

    @Test
    void sameOccurrenceDeduplicatesAcrossScheduleRevision() throws Exception {
        setup("/ok");
        Instant at = clock.instant();
        var s = schedules.get(platform);
        runs.automatic(platform, s, at);
        await(() -> runs.active().isEmpty());
        schedules.save(
                platform,
                new ScheduleSpec(
                        false,
                        "daily",
                        s.weekdays(),
                        List.of("09:00", "10:00"),
                        s.timezone(),
                        0,
                        120,
                        true,
                        s.revision()));
        runs.automatic(platform, schedules.get(platform), at);
        assertEquals(
                1,
                db.count(
                        "SELECT COUNT(*) FROM run_batches WHERE platform_id=? AND source='auto'",
                        platform));
        assertEquals(1, hits.get());
    }

    @Test
    void cancelsPendingAndRefusesDeletingActiveRequests() throws Exception {
        setup("/hold");
        catalog.addRequest(account, "第二请求", "curl '" + url + "/ok'", ResultRules.defaults(), true);
        String batch =
                runs.manual(
                                new RunService.ManualRun(
                                        "platform", platform, false, UUID.randomUUID().toString()))
                        .getFirst();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(ApiException.class, () -> catalog.delete("platforms", platform));
        runs.cancel(batch);
        release.countDown();
        complete(batch);
        var items = (List<Map<String, Object>>) runs.batch(batch).get("items");
        assertEquals("success", Db.text(items.get(0), "status"));
        assertEquals("cancelled", Db.text(items.get(1), "status"));
        assertEquals(1, hits.get());
    }

    @Test
    void restartRecoveryMarksRunningUnknown() throws Exception {
        setup("/ok");
        String batch = manual(false);
        complete(batch);
        db.update("UPDATE run_items SET status='running' WHERE batch_id=?", batch);
        db.update("UPDATE run_batches SET status='running' WHERE id=?", batch);
        runs.recover();
        assertEquals("unknown", status(batch));
        assertEquals(
                1,
                db.count(
                        "SELECT COUNT(*) FROM request_day_states WHERE request_id=? AND"
                                + " unknown_pending=1",
                        request));
        await(() -> runs.active().isEmpty());
        assertEquals(1, hits.get());
    }

    @Test
    void encryptedBackupIsPortableToDifferentMasterKeyAndRejectsWrongPassword() throws Exception {
        setup("/ok");
        String template = catalog.addTemplate(platform, "签到模板", ResultRules.defaults());
        String batch = manual(false);
        complete(batch);
        String password = "local-test-backup-password";
        var previous = settings.get();
        var proxy = new ProxySettings("http", "127.0.0.1", 12345);
        settings.save(new SettingsService.Settings(true, 3, 7, 45, previous.version(), proxy));
        var encrypted =
                (Map<String, Object>)
                        backups.export(new BackupService.Request(password, true, null, false));
        assertFalse(Json.write(encrypted).contains("test-only-credential"));
        catalog.updateTemplate(platform, template, "导出后修改", ResultRules.defaults(), 1);
        assertThrows(
                ApiException.class,
                () -> backups.restore(new BackupService.Request("wrong", false, encrypted, true)));
        assertEquals(1, db.count("SELECT COUNT(*) FROM platforms"));
        var different =
                new SecretStore(
                        migrator,
                        lock,
                        Base64.getEncoder().encodeToString(new byte[32]),
                        "ignored");
        var destination =
                new BackupService(db, catalog, different, runs, parser, clock, transactions);
        destination.restore(new BackupService.Request(password, false, encrypted, true));
        var restoredCatalog = new CatalogService(db, mapper, parser, different, clock);
        assertTrue(restoredCatalog.revision(request, 1).rawCurl().contains("test-only-credential"));
        assertThrows(IllegalStateException.class, () -> catalog.revision(request, 1));
        assertTrue(settings.get().paused());
        assertEquals(3, settings.get().concurrency());
        assertEquals(7, settings.get().timeoutSeconds());
        assertEquals(45, settings.get().retentionDays());
        assertEquals(proxy, settings.get().proxy());
        assertEquals(template, catalog.templates(platform).getFirst().get("id"));
        assertEquals("签到模板", catalog.templates(platform).getFirst().get("name"));
        assertEquals(ResultRules.defaults(), catalog.templates(platform).getFirst().get("rules"));
        assertEquals(
                1, db.count("SELECT COUNT(*) FROM daily_completions WHERE request_id=?", request));
    }

    @Test
    void invalidTemplateBackupsAreRejectedBeforeReplacingData() {
        setup("/ok");
        String template = catalog.addTemplate(platform, "有效模板", ResultRules.defaults());
        var exported = (Map<String, Object>) backups.export(new BackupService.Request(null, false, null, false));
        var invalidRules = new ResultRules(new ResultRules.Match("bad[*]", 0, null), null, null, null);
        for (var change : List.of(
                Map.of("platformId", "1"), Map.of("id", "9223372036854775808"),
                Map.of("id", "0"), Map.of("name", " "), Map.of("rules", invalidRules))) {
            var damaged = Json.map(Json.write(exported));
            var payload = (Map<String, Object>) damaged.get("payload");
            var templates = (List<Map<String, Object>>) payload.get("templates");
            templates.getFirst().putAll(change);
            assertThrows(ApiException.class,
                    () -> backups.restore(new BackupService.Request(null, false, damaged, true)));
            assertEquals(template, catalog.templates(platform).getFirst().get("id"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM requests"));
            assertTrue(catalog.revision(request, 1).rawCurl().contains("test-only-credential"));
        }
        var duplicate = Json.map(Json.write(exported));
        var payload = (Map<String, Object>) duplicate.get("payload");
        var templates = (List<Map<String, Object>>) payload.get("templates");
        templates.add(templates.getFirst());
        assertThrows(ApiException.class,
                () -> backups.restore(new BackupService.Request(null, false, duplicate, true)));
        assertEquals(1, catalog.templates(platform).size());
    }

    @Test
    void plainExportContainsNoRequestAndRestoreDisablesPlaceholders() {
        setup("/ok");
        String template = catalog.addTemplate(platform, "积分模板", ResultRules.defaults());
        var config =
                (Map<String, Object>)
                        backups.export(new BackupService.Request(null, false, null, false));
        String text = Json.write(config);
        assertFalse(text.contains("test-only-credential"));
        assertFalse(text.contains("first-body"));
        assertFalse(text.contains(url));
        catalog.deleteTemplate(platform, template);
        backups.restore(new BackupService.Request(null, false, config, true));
        assertEquals(template, catalog.templates(platform).getFirst().get("id"));
        assertEquals(ResultRules.defaults(), catalog.templates(platform).getFirst().get("rules"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM request_revisions"));
        assertFalse(Db.flag(db.one("SELECT enabled FROM requests WHERE id=?", request), "enabled"));
    }

    @Test
    void singleInstanceAndMissingKeyFailWithoutOverwritingData() throws Exception {
        setup("/ok");
        assertThrows(IllegalStateException.class, () -> new InstanceLock(lock.directory()));
        Path missing = DIR.resolve("missing-key/master.key");
        assertThrows(
                IllegalStateException.class,
                () -> new SecretStore(migrator, lock, "", missing.toString()));
        assertFalse(Files.exists(missing));
        var error =
                assertThrows(
                        ApiException.class,
                        () -> catalog.updatePlatform(platform, "其他名称", "", true, 99));
        assertEquals(409, error.status());
    }
}
