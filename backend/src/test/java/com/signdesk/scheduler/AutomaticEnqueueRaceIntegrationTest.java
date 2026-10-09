package com.signdesk.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.common.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.BackupFile;
import com.signdesk.engine.*;
import com.signdesk.service.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AutomaticEnqueueRaceIntegrationTest.TimeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomaticEnqueueRaceIntegrationTest {
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("signdesk-auto-race-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signdesk.data-dir", () -> DIR.toString());
        registry.add("signdesk.allowed-hosts", () -> "127.0.0.1");
        // The real dispatch still runs. Scans in these tests are started explicitly at a latch
        // boundary.
        registry.add("signdesk.scan-delay-ms", () -> 3600000);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock raceClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-10-09T00:00:00Z"));

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }
    }

    @Autowired IPlatformService platforms;
    @Autowired IAccountService accounts;
    @Autowired IRequestService requests;
    @Autowired IScheduleService schedules;
    @Autowired ISettingsService settings;
    @Autowired IRunService runs;
    @Autowired IRunRecordService records;
    @Autowired IBackupService backups;
    @Autowired RunCoordinator coordinator;
    @Autowired RunDispatcher dispatcher;
    @Autowired ScheduleScanner scanner;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    HttpServer fixture;
    String url, platform, account, request;
    final AtomicInteger beforeHits = new AtomicInteger(), importedHits = new AtomicInteger();
    CountDownLatch holdEntered, holdRelease;

    @BeforeAll
    void fixture() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/before", e -> reply(e, beforeHits));
        fixture.createContext("/imported", e -> reply(e, importedHits));
        fixture.createContext(
                "/hold",
                e -> {
                    holdEntered.countDown();
                    try {
                        if (!holdRelease.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("fixture hold expired");
                        reply(e, beforeHits);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        e.close();
                    }
                });
        fixture.start();
        url = "http://127.0.0.1:" + fixture.getAddress().getPort();
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, AtomicInteger hits)
            throws java.io.IOException {
        hits.incrementAndGet();
        byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterAll
    void stopFixture() {
        fixture.stop(0);
    }

    @BeforeEach
    void setup() throws Exception {
        await(() -> records.active().isEmpty());
        await(
                () -> {
                    try {
                        runs.beginMaintenance();
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                });
        try {
            jdbc.update("DELETE FROM platforms");
        } finally {
            runs.endMaintenance();
        }
        clock.set(Instant.parse("2026-10-09T00:00:00Z"));
        beforeHits.set(0);
        importedHits.set(0);
        holdEntered = new CountDownLatch(1);
        holdRelease = new CountDownLatch(1);
        var p = new PlatformBo();
        p.setName("扫描竞态平台");
        p.setNote("");
        p.setEnabled(true);
        platform = platforms.insert(p);
        account = accounts.insert(platform, new NewAccountBo("测试账号", true));
        request =
                requests.insert(
                        account,
                        new NewRequestBo(
                                "请求", "curl '" + url + "/before'", true, ResultRules.defaults()));
        // A valid current revision 1; import resets to the same revision and, with the fixed Clock,
        // the same effectiveFrom. Neither can stand in for a maintenance-generation token.
        jdbc.update(
                "UPDATE platform_schedules SET"
                    + " enabled=1,times_json='[\"08:00\"]',catchup_minutes=120,effective_from=?"
                    + " WHERE platform_id=?",
                clock.instant().toString(),
                platform);
        pause(false);
    }

    void pause(boolean paused) {
        var s = settings.query();
        settings.save(
                new SettingsBo(
                        paused,
                        s.concurrency(),
                        s.timeoutSeconds(),
                        s.retentionDays(),
                        s.version(),
                        s.proxy()));
    }

    BackupFile importFile() {
        var file = backups.export(new BackupExportBo(true));
        var s = file.payload();
        var entries =
                s.requests().stream()
                        .map(
                                r ->
                                        new BackupFile.RequestEntry(
                                                r.id(),
                                                r.accountId(),
                                                r.name(),
                                                r.enabled(),
                                                r.authPaused(),
                                                r.sortOrder(),
                                                r.rules(),
                                                "curl '" + url + "/imported'"))
                        .toList();
        return new BackupFile(
                file.format(),
                new BackupFile.Payload(
                        s.includesRequests(),
                        s.settings(),
                        s.platforms(),
                        s.accounts(),
                        entries,
                        s.templates(),
                        s.schedules(),
                        s.completed(),
                        s.pending()));
    }

    final class BlockedScan implements AutoCloseable {
        private final CountDownLatch readPlan = new CountDownLatch(1),
                continueScan = new CountDownLatch(1);
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private final Future<?> future;

        BlockedScan() throws Exception {
            // Only delay the call boundary. All schedule reads, backup writes, queue operations and
            // HTTP executions still delegate to the real services and SQLite; no production hook.
            IRunService delayed =
                    (IRunService)
                            Proxy.newProxyInstance(
                                    IRunService.class.getClassLoader(),
                                    new Class<?>[] {IRunService.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("automatic")) {
                                            readPlan.countDown();
                                            if (!continueScan.await(10, TimeUnit.SECONDS))
                                                throw new IllegalStateException(
                                                        "fixture latch expired");
                                        }
                                        try {
                                            return method.invoke(runs, args);
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
            var delayedScanner =
                    new ScheduleScanner(schedules, delayed, records, settings, coordinator, clock);
            future = thread.submit(delayedScanner::scan);
            assertTrue(
                    readPlan.await(5, TimeUnit.SECONDS),
                    "scanner did not reach automatic after reading the due plan");
        }

        void resume() throws Exception {
            continueScan.countDown();
            future.get(5, TimeUnit.SECONDS);
            dispatcher.dispatch();
            if (batchCount() != 0) await(() -> records.active().isEmpty());
        }

        @Override
        public void close() throws Exception {
            continueScan.countDown();
            try {
                future.get(5, TimeUnit.SECONDS);
            } finally {
                thread.shutdownNow();
            }
        }
    }

    long batchCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM run_batches", Long.class);
    }

    @Test
    void staleScanCannotEnqueueImportedRequestWhilePaused() throws Exception {
        var file = importFile();
        try (var scan = new BlockedScan()) {
            backups.restore(new BackupImportBo(file, true));
            assertTrue(settings.query().paused());
            assertEquals(1, schedules.queryById(platform).revision());
            assertEquals(
                    clock.instant().toString(),
                    jdbc.queryForObject(
                            "SELECT effective_from FROM platform_schedules", String.class));
            scan.resume();
            assertAll(
                    () -> assertEquals(0, batchCount(), "old scan enqueued after replace"),
                    () -> assertEquals(0, importedHits.get(), "old scan sent the imported cURL"),
                    () -> assertEquals(0, beforeHits.get()),
                    () -> assertTrue(settings.query().paused()));
        }
    }

    @Test
    void
            maintenanceGenerationRejectsOldScanEvenAfterImmediateResumeWithSameRevisionAndEffectiveTime()
                    throws Exception {
        var file = importFile();
        try (var scan = new BlockedScan()) {
            backups.restore(new BackupImportBo(file, true));
            pause(false);
            assertEquals(1, schedules.queryById(platform).revision());
            scan.resume();
            assertAll(
                    () -> assertEquals(0, batchCount()),
                    () -> assertEquals(0, importedHits.get()),
                    () -> assertFalse(settings.query().paused()));
        }
        scanner.scan();
        await(() -> importedHits.get() == 1 && records.active().isEmpty());
        assertEquals(1, batchCount(), "a fresh scan must remain usable after import/resume");
        assertEquals(0, beforeHits.get());
    }

    @Test
    void pauseCommittedAfterPlanReadIsRecheckedAtTheAutomaticTransactionBoundary()
            throws Exception {
        try (var scan = new BlockedScan()) {
            pause(true);
            scan.resume();
            assertAll(
                    () -> assertEquals(0, batchCount()),
                    () -> assertEquals(0, beforeHits.get()),
                    () -> assertTrue(settings.query().paused()));
        }
    }

    @Test
    void ordinaryPlanEditInvalidatesOldScanButANewCurrentOccurrenceCanRun() throws Exception {
        try (var scan = new BlockedScan()) {
            var s = schedules.queryById(platform);
            schedules.save(
                    platform,
                    new ScheduleBo(
                            true,
                            s.frequency(),
                            s.weekdays(),
                            List.of("08:30"),
                            s.timezone(),
                            0,
                            120,
                            false,
                            s.revision()));
            scan.resume();
            assertAll(() -> assertEquals(0, batchCount()), () -> assertEquals(0, beforeHits.get()));
        }
        clock.set(Instant.parse("2026-10-09T00:30:00Z"));
        scanner.scan();
        await(() -> beforeHits.get() == 1 && records.active().isEmpty());
        assertEquals(1, batchCount());
        assertEquals(
                2, jdbc.queryForObject("SELECT schedule_revision FROM run_batches", Integer.class));
    }

    @Test
    void persistedBatchKeepsRunningItsFrozenQueueAfterPauseAndPlanDisable() throws Exception {
        requests.replaceCurl(request, new CurlBo("curl '" + url + "/hold'", 1));
        requests.insert(
                account,
                new NewRequestBo(
                        "第二请求", "curl '" + url + "/imported'", true, ResultRules.defaults()));
        scanner.scan();
        assertTrue(holdEntered.await(5, TimeUnit.SECONDS));
        try {
            pause(true);
            var s = schedules.queryById(platform);
            schedules.save(
                    platform,
                    new ScheduleBo(
                            false,
                            s.frequency(),
                            s.weekdays(),
                            s.times(),
                            s.timezone(),
                            0,
                            s.catchupMinutes(),
                            s.skipCompletedDaily(),
                            s.revision()));
        } finally {
            holdRelease.countDown();
        }
        await(() -> records.active().isEmpty());
        assertEquals(1, beforeHits.get());
        assertEquals(1, importedHits.get());
        assertEquals(1, batchCount());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM run_items WHERE status='success'", Integer.class));
        assertTrue(settings.query().paused());
        scanner.scan();
        assertEquals(
                1, batchCount(), "the pause blocks new scans, not the already-persisted batch");
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean finished = condition.getAsBoolean();
        while (!finished && System.nanoTime() < deadline) {
            Thread.sleep(20);
            finished = condition.getAsBoolean();
        }
        assertTrue(finished, "fixture operation did not finish");
    }
}
