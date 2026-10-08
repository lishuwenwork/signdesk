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
        var exported = (Map<String, Object>) backups.export(new BackupService.Request(null, false, null, false));
        var backup = Json.map(Json.write(exported));
        var payload = (Map<String, Object>) backup.get("payload");
        ((Map<String, Object>) payload.get("settings")).remove("proxy");
        var previous = settings.get();
        settings.save(new SettingsService.Settings(true, 2, 20, 30, previous.version(),
                new ProxySettings("http", "127.0.0.1", 12345)));
        backups.restore(new BackupService.Request(null, false, backup, true));
        assertEquals(ProxySettings.system(), settings.get().proxy());
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
        assertEquals(
                1, db.count("SELECT COUNT(*) FROM daily_completions WHERE request_id=?", request));
    }

    @Test
    void plainExportContainsNoRequestAndRestoreDisablesPlaceholders() {
        setup("/ok");
        var config =
                (Map<String, Object>)
                        backups.export(new BackupService.Request(null, false, null, false));
        String text = Json.write(config);
        assertFalse(text.contains("test-only-credential"));
        assertFalse(text.contains("first-body"));
        assertFalse(text.contains(url));
        backups.restore(new BackupService.Request(null, false, config, true));
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
