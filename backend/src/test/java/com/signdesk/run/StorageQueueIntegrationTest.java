package com.signdesk.run;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.common.*;
import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.SchedulePlan;
import com.signdesk.domain.vo.*;
import com.signdesk.engine.*;
import com.signdesk.mapper.*;
import com.signdesk.scheduler.*;
import com.signdesk.service.*;
import com.signdesk.storage.*;
import com.sun.net.httpserver.HttpServer;

import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(StorageQueueIntegrationTest.TimeConfiguration.class)
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
        registry.add("signdesk.allowed-hosts", () -> "127.0.0.1");
    }

    @Autowired IPlatformService platforms;
    @Autowired IAccountService accounts;
    @Autowired IRequestService requests;
    @Autowired IRequestTemplateService templates;
    @Autowired IRunService runs;
    @Autowired IRunRecordService records;
    @Autowired ISettingsService settings;
    @Autowired IScheduleService schedules;
    @Autowired IBackupService backups;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScheduleScanner scanner;
    @Autowired InstanceLock lock;
    @Autowired PlatformMapper mapper;
    @Autowired RunResponseMapper responseMapper;
    @Autowired SqlSessionTemplate sqlSession;
    @Autowired Clock clock;
    @Autowired RunCoordinator coordinator;
    @Autowired RunDispatcher dispatcher;
    @Autowired MutableClock testClock;
    @Autowired ISystemService system;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-10-09T00:00:00Z"));
        private final ThreadLocal<Runnable> instantObserver = new ThreadLocal<>();

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public Instant instant() {
            var observer = instantObserver.get();
            if (observer != null) observer.run();
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
        server.createContext(
                "/response",
                e -> {
                    byte[] reply =
                            responseText(hits.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.createContext(
                "/response-error",
                e -> {
                    hits.incrementAndGet();
                    byte[] reply = "upstream-response-only-secret".getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                    e.sendResponseHeaders(500, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.createContext(
                "/response-gbk",
                e -> {
                    hits.incrementAndGet();
                    byte[] reply = "响应体中文".getBytes(Charset.forName("GBK"));
                    e.getResponseHeaders().add("Content-Type", "text/plain; charset=GBK");
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.createContext(
                "/response-binary",
                e -> {
                    hits.incrementAndGet();
                    byte[] reply = {0, 1, 2, (byte) 255};
                    e.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.createContext(
                "/response-partial",
                e -> {
                    hits.incrementAndGet();
                    try {
                        e.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                        e.sendResponseHeaders(200, 0);
                        e.getResponseBody()
                                .write(
                                        "partial-response-only-secret"
                                                .getBytes(StandardCharsets.UTF_8));
                        e.getResponseBody().flush();
                        Thread.sleep(1500);
                    } catch (Exception ignored) {
                    } finally {
                        e.close();
                    }
                });
        server.createContext(
                "/response-large",
                e -> {
                    hits.incrementAndGet();
                    byte[] reply = new byte[1048577];
                    Arrays.fill(reply, (byte) 'z');
                    e.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                    e.sendResponseHeaders(200, reply.length);
                    try {
                        e.getResponseBody().write(reply);
                    } catch (Exception ignored) {
                    }
                    e.close();
                });
        server.createContext(
                "/response-exact",
                e -> {
                    hits.incrementAndGet();
                    byte[] reply = new byte[1048576];
                    Arrays.fill(reply, (byte) 'e');
                    e.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    void stopFixture() throws Exception {
        server.stop(0);
    }

    String platform, account, request;

    @BeforeEach
    void reset() throws Exception {
        await(() -> records.active().isEmpty());
        // Active database rows can complete before the platform worker releases its in-process
        // slot.
        for (int i = 0; i < 100; i++) {
            try {
                runs.beginMaintenance();
                break;
            } catch (ApiException e) {
                if (i == 99) throw e;
                Thread.sleep(10);
            }
        }
        try {
            jdbc.update("DELETE FROM platforms");
        } finally {
            runs.endMaintenance();
        }
        testClock.set(Instant.parse("2026-10-09T00:00:00Z"));
        var s = settings.query();
        settings.save(new SettingsBo(true, 2, 20, 30, s.version(), ProxySettings.system()));
        hits.set(0);
        bodies.clear();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    String addPlatform(String name, String note, boolean enabled) {
        var b = new PlatformBo();
        b.setName(name);
        b.setNote(note);
        b.setEnabled(enabled);
        return platforms.insert(b);
    }

    void updatePlatform(String id, String name, String note, boolean enabled, int version) {
        var b = new PlatformBo();
        b.setName(name);
        b.setNote(note);
        b.setEnabled(enabled);
        b.setVersion(version);
        platforms.update(id, b);
    }

    String addAccount(String p, String alias, boolean enabled) {
        return accounts.insert(p, new NewAccountBo(alias, enabled));
    }

    String addRequest(String a, String name, String curl, ResultRules rules, boolean enabled) {
        return requests.insert(a, new NewRequestBo(name, curl, enabled, rules));
    }

    String addTemplate(String p, String name, ResultRules rules) {
        return templates.insert(p, new NewRequestTemplateBo(name, rules));
    }

    void setup(String endpoint) {
        platform = addPlatform("测试平台", "", true);
        account = addAccount(platform, "账号 A", true);
        request =
                addRequest(
                        account,
                        "每日签到",
                        "curl '"
                                + url
                                + endpoint
                                + "' -H 'Cookie: test-only-credential=alpha' --data-raw"
                                + " 'first-body'",
                        ResultRules.defaults(),
                        true);
        var s = schedules.queryById(platform);
        schedules.save(
                platform,
                new ScheduleBo(
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
        return runs.manual(new ManualRunBo("request", request, force, UUID.randomUUID().toString()))
                .getFirst();
    }

    void complete(String batch) throws Exception {
        await(() -> records.queryBatch(batch).getStatus().equals("completed"));
    }

    String status(String batch) {
        return records.queryBatch(batch).getItems().getFirst().getStatus();
    }

    String itemId(String batch) {
        return records.queryBatch(batch).getItems().getFirst().getId();
    }

    ResponseDetailVo response(String batch) {
        return records.queryResponse(itemId(batch));
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    int integer(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    static String responseText(int attempt) {
        return "{\n  \"code\":0,\n  \"attempt\":"
                + attempt
                + ",\n  \"message\":\"响应体 response-only-fixture-secret\"\n}";
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(30);
        assertTrue(condition.getAsBoolean(), "operation did not finish");
    }

    RequestVo listedRequest(String id) {
        return platforms.queryList().stream()
                .flatMap(p -> p.getAccounts().stream())
                .flatMap(a -> a.getRequests().stream())
                .filter(r -> r.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    String insertHistory(String p, String r, String status, String createdAt, String finishedAt) {
        String batch = Ids.next(), item = Ids.next();
        jdbc.update(
                "INSERT INTO run_batches(id,platform_id,schedule_revision,business_date,timezone,"
                        + "source,interval_seconds,status,created_at) VALUES(?,?,1,'2026-10-09',"
                        + "'Asia/Shanghai','manual',0,'completed',?)",
                batch, p, createdAt);
        jdbc.update(
                "INSERT INTO run_items(id,batch_id,request_id,request_revision,rules_json,ordinal,"
                        + "status,http_status,duration_ms,safe_summary,finished_at)"
                        + " VALUES(?,?,?,1,'{}',0,?,207,123,'summary-only-private-fixture',?)",
                item, batch, r, status, finishedAt);
        return item;
    }

    @Test
    void platformTreeTodayStateUsesClockAndEachPlatformTimezoneWithCompletedPriority() {
        setup("/ok");
        String utcPlatform = addPlatform("UTC平台", "", true);
        var s = schedules.queryById(utcPlatform);
        schedules.save(utcPlatform, new ScheduleBo(false, "daily", s.weekdays(), s.times(),
                "UTC", 0, 0, true, s.revision()));
        String utcRequest = addRequest(addAccount(utcPlatform, "UTC账号", true), "UTC请求",
                "curl '" + url + "/ok'", ResultRules.defaults(), true);
        String untouched = addRequest(account, "无日标记", "curl '" + url + "/ok'",
                ResultRules.defaults(), true);
        for (String r : List.of(request, utcRequest)) {
            jdbc.update("INSERT INTO request_day_states VALUES(?,?,1)", r, "2026-10-09");
            jdbc.update("INSERT INTO request_day_states VALUES(?,?,1)", r, "2026-10-10");
            jdbc.update("INSERT INTO daily_completions VALUES(?,?,?)", r, "2026-10-10", Ids.next());
        }
        jdbc.update("INSERT INTO request_day_states VALUES(?,?,0)", untouched, "2026-10-10");
        jdbc.update("INSERT INTO daily_completions VALUES(?,?,?)", untouched, "2026-10-08", Ids.next());
        testClock.set(Instant.parse("2026-10-09T15:59:59Z"));
        assertEquals("pending", listedRequest(request).getTodayState());
        assertEquals("pending", listedRequest(utcRequest).getTodayState());
        testClock.set(Instant.parse("2026-10-09T16:00:00Z"));
        assertEquals("completed", listedRequest(request).getTodayState());
        assertEquals("pending", listedRequest(utcRequest).getTodayState());
        assertEquals("none", listedRequest(untouched).getTodayState());
        testClock.set(Instant.parse("2026-10-10T00:00:00Z"));
        assertEquals("completed", listedRequest(utcRequest).getTodayState());
        assertNull(listedRequest(request).getLastRun());
        assertNull(listedRequest(utcRequest).getLastRun());
        assertNull(listedRequest(untouched).getLastRun());
        assertEquals(0, hits.get());
    }

    @Test
    void platformTreeKeepsIndependentDayMarkersAfterExecutionLogsAndResponsesAreCleared() {
        setup("/ok");
        String pending = addRequest(account, "待确认请求", "curl '" + url + "/ok'",
                ResultRules.defaults(), true);
        String oldTime = "2026-08-01T00:00:00Z";
        String completedItem = insertHistory(platform, request, "success", oldTime, oldTime);
        String unknownItem = insertHistory(platform, pending, "unknown", oldTime, oldTime);
        jdbc.update("INSERT INTO daily_completions VALUES(?,?,?)", request, "2026-10-09", completedItem);
        jdbc.update("INSERT INTO request_day_states VALUES(?,?,1)", pending, "2026-10-09");
        for (String item : List.of(completedItem, unknownItem)) {
            jdbc.update("INSERT INTO run_responses(run_id,body_bytes,capture_state) VALUES(?,?,'complete')",
                    item, "response-only-private-fixture".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(completedItem, listedRequest(request).getLastRun().getId());
        assertEquals(unknownItem, listedRequest(pending).getLastRun().getId());
        records.clearExpired();
        assertEquals(0, count("SELECT COUNT(*) FROM run_items"));
        assertEquals(0, count("SELECT COUNT(*) FROM run_responses"));
        assertEquals(1, count("SELECT COUNT(*) FROM daily_completions"));
        assertEquals(1, count("SELECT COUNT(*) FROM request_day_states"));
        assertEquals("completed", listedRequest(request).getTodayState());
        assertEquals("pending", listedRequest(pending).getTodayState());
        assertNull(listedRequest(request).getLastRun());
        assertNull(listedRequest(pending).getLastRun());
    }

    @Test
    void platformTreeLastRunIsDeterministicSafeMetadataAndNeverDerivesDayMarkersFromLogs() {
        setup("/ok");
        insertHistory(platform, request, "success", "2026-10-08T00:00:00Z", "2026-10-09T00:05:00Z");
        String created = "2026-10-09T00:00:00Z";
        insertHistory(platform, request, "failed", created, "2026-10-09T00:04:00Z");
        String newest = insertHistory(platform, request, "unknown", created, "2026-10-09T00:01:00Z");
        // Reverse ID ordering: ties use insertion order (SQLite rowid), not lexical IDs or finish time.
        jdbc.update("UPDATE run_items SET id='1' WHERE id=?", newest);
        jdbc.update("INSERT INTO run_responses(run_id,body_bytes,capture_state) VALUES('1',?,'complete')",
                "response-only-private-fixture".getBytes(StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < 3; attempt++) {
            var vo = listedRequest(request);
            assertEquals("none", vo.getTodayState());
            var last = vo.getLastRun();
            assertEquals("1", last.getId());
            assertEquals("unknown", last.getStatus());
            assertEquals(created, last.getCreatedAt());
            assertEquals("2026-10-09T00:01:00Z", last.getFinishedAt());
            assertEquals(207, last.getHttpStatus());
            assertEquals(123L, last.getDurationMs());
            assertEquals(Set.of("id", "status", "finishedAt", "createdAt", "httpStatus", "durationMs"),
                    Json.map(Json.write(last)).keySet());
            String json = Json.write(platforms.queryList());
            assertFalse(json.contains("response-only-private-fixture"));
            assertFalse(json.contains("summary-only-private-fixture"));
            assertFalse(json.contains("test-only-credential"));
            assertFalse(json.contains("bodyBytes"));
            assertFalse(json.contains("safeSummary"));
            assertFalse(json.contains("rawCurl"));
        }
    }

    @Test
    void platformTreeLastRunOrdersUtcSecondsAndNanosecondsWithoutTimestampRounding() {
        setup("/ok");
        java.util.function.Supplier<List<String>> logOrder = () ->
                records.queryPageList(platform, null, null, 1, 100).items().stream()
                        .map(RunLogVo::getId).toList();
        String millis = insertHistory(platform, request, "success",
                "2026-10-09T00:00:00.100Z", null);
        String seconds = insertHistory(platform, request, "failed", "2026-10-09T00:00:00Z", null);
        assertEquals(millis, listedRequest(request).getLastRun().getId());
        assertEquals(List.of(millis, seconds), logOrder.get());
        String micros = insertHistory(platform, request, "success",
                "2026-10-09T00:00:00.100001Z", null);
        assertEquals(micros, listedRequest(request).getLastRun().getId());
        assertEquals(List.of(micros, millis, seconds), logOrder.get());
        String nanos = insertHistory(platform, request, "success",
                "2026-10-09T00:00:00.100001002Z", null);
        String lowerNanos = insertHistory(platform, request, "failed",
                "2026-10-09T00:00:00.100001001Z", null);
        String tiedMicros = insertHistory(platform, request, "failed",
                "2026-10-09T00:00:00.100001Z", null);
        assertEquals(nanos, listedRequest(request).getLastRun().getId());
        assertEquals(List.of(nanos, lowerNanos, tiedMicros, micros, millis, seconds), logOrder.get());
        String tie = insertHistory(platform, request, "unknown",
                "2026-10-09T00:00:00.100001002Z", null);
        assertEquals(tie, listedRequest(request).getLastRun().getId());
        assertEquals(List.of(tie, nanos, lowerNanos, tiedMicros, micros, millis, seconds), logOrder.get());
        String nextSecond = insertHistory(platform, request, "success",
                "2026-10-09T00:00:01Z", null);
        String previousSecond = insertHistory(platform, request, "failed",
                "2026-10-09T00:00:00.999999999Z", null);
        assertEquals(nextSecond, listedRequest(request).getLastRun().getId());
        assertEquals(List.of(nextSecond, previousSecond, tie, nanos, lowerNanos, tiedMicros,
                micros, millis, seconds), logOrder.get());
        assertEquals("2026-10-09T00:00:01Z", listedRequest(request).getLastRun().getCreatedAt());

        // The real SQLite plan uses each request's existing index range. It still scans that
        // request's retained history, but must not materialize a full-history window result.
        var statement = sqlSession.getConfiguration().getMappedStatement(
                "com.signdesk.mapper.RequestStatusQueryMapper.queryAll");
        var sql = statement.getBoundSql(Map.of(
                "shanghaiDate", "2026-10-09", "utcDate", "2026-10-09")).getSql();
        var plan = jdbc.query("EXPLAIN QUERY PLAN " + sql,
                (row, index) -> row.getString("detail"), "2026-10-09", "2026-10-09");
        assertTrue(plan.stream().anyMatch(line -> line.contains(
                "SEARCH candidate USING INDEX items_request")), plan.toString());
        assertTrue(plan.stream().anyMatch(line -> line.contains(
                "CORRELATED SCALAR SUBQUERY")), plan.toString());
        assertFalse(plan.stream().anyMatch(line -> line.contains("MATERIALIZE")
                || line.contains("CO-ROUTINE")), plan.toString());
    }

    @Test
    void platformTreeQueryRunsInsideReadOnlyTransactionAndReleasesItAfterReturning() {
        setup("/ok");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var observed = new AtomicInteger();
        // Observe the actual proxied service call, not merely the annotation metadata. A
        // thread-local probe ignores unrelated scheduler/worker Clock calls.
        testClock.instantObserver.set(() -> {
            observed.incrementAndGet();
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertTrue(TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
        });
        try {
            assertEquals(request, listedRequest(request).getId());
        } finally {
            testClock.instantObserver.remove();
        }
        assertEquals(1, observed.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
    }

    BackupFile config(boolean include) {
        return backups.export(new BackupExportBo(include));
    }

    BackupFile file(Object value) {
        return BackupCodec.read(
                        new ByteArrayInputStream(
                                Json.write(
                                                new BackupPreviewBo(
                                                        Json.read(
                                                                Json.write(value),
                                                                BackupFile.class)))
                                        .getBytes(StandardCharsets.UTF_8)),
                        BackupPreviewBo.class)
                .backup();
    }

    BackupFile decodeMap(Map<String, Object> value) {
        return BackupCodec.read(
                        new ByteArrayInputStream(
                                Json.write(Map.of("backup", value))
                                        .getBytes(StandardCharsets.UTF_8)),
                        BackupPreviewBo.class)
                .backup();
    }

    void restore(BackupFile b) {
        backups.restore(new BackupImportBo(b, true));
    }

    @Test
    void mpPrivateEntitySupportsStringIdsLambdaQueriesAndOptimisticEdits() {
        String id = addPlatform("MP基础测试", "initial", true);
        var first = mapper.selectById(id);
        var stale = mapper.selectById(id);
        assertTrue(id.matches("[1-9][0-9]*"));
        assertEquals(id, first.getId());
        assertEquals("MP基础测试", platforms.queryList().getFirst().getName());
        first.setNote("updated");
        assertEquals(1, mapper.updateById(first));
        assertEquals(2, first.getVersion());
        stale.setNote("must-not-overwrite");
        assertEquals(0, mapper.updateById(stale));
        assertEquals("updated", mapper.selectById(id).getNote());
        updatePlatform(id, "MP基础测试", "service-edit", true, 2);
        assertEquals(3, mapper.selectById(id).getVersion());
        assertEquals(
                409,
                assertThrows(ApiException.class, () -> updatePlatform(id, "stale", "", true, 2))
                        .status());
    }

    @Test
    void mapperXmlIsDiscoveredAndSqlParameterLoggingStaysOff() {
        String id = addPlatform("XML测试", "", true);
        assertEquals(NoLoggingImpl.class, sqlSession.getConfiguration().getLogImpl());
        Integer version =
                sqlSession.selectOne("com.signdesk.mapper.PlatformMapper.foundationVersion", id);
        assertEquals(1, version);
        assertNull(
                sqlSession.selectOne(
                        "com.signdesk.mapper.PlatformMapper.foundationVersion", "' OR 1=1 --"));
    }

    @Test
    void responseBodiesArePlainAndRemainIndependentForEveryExecution() throws Exception {
        setup("/response");
        String first = manual(false);
        complete(first);
        var firstBody = response(first);
        assertEquals("complete", firstBody.state());
        assertEquals(responseText(1), firstBody.body());
        assertEquals("text", firstBody.encoding());
        assertEquals(
                responseText(1).getBytes(StandardCharsets.UTF_8).length, firstBody.byteLength());
        String second = manual(true);
        complete(second);
        assertEquals(responseText(2), response(second).body());
        assertEquals(firstBody, response(first));
        assertArrayEquals(
                responseText(1).getBytes(StandardCharsets.UTF_8),
                jdbc.queryForObject(
                        "SELECT body_bytes FROM run_responses WHERE run_id=?",
                        byte[].class,
                        itemId(first)));
        assertFalse(
                Json.write(records.queryPageList(null, null, null, 1, 20))
                        .contains("response-only-fixture-secret"));
        assertFalse(Json.write(records.queryBatch(first)).contains("response-only-fixture-secret"));
        assertFalse(Json.write(config(false)).contains("response-only-fixture-secret"));
        assertEquals(firstBody, records.queryById(itemId(first)).getResponse());
        assertEquals(2, count("SELECT COUNT(*) FROM run_responses"));
        assertEquals(2, hits.get());
    }

    @Test
    void failedEmptySkippedAndUnavailableResponsesHaveDistinctDetails() throws Exception {
        setup("/response-error");
        String failed = manual(false);
        complete(failed);
        assertEquals("unknown", status(failed));
        assertEquals("complete", response(failed).state());
        assertEquals("upstream-response-only-secret", response(failed).body());
        String skipped = manual(false);
        complete(skipped);
        assertEquals("skipped", status(skipped));
        assertEquals("not_recorded", response(skipped).state());
        assertEquals(1, hits.get());
        requests.replaceCurl(request, new CurlBo("curl '" + url + "/expired'", 1));
        String empty = manual(true);
        complete(empty);
        assertEquals("expired", status(empty));
        assertEquals("complete", response(empty).state());
        assertEquals("", response(empty).body());
        assertEquals(0, response(empty).byteLength());
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        int version = integer("SELECT version FROM requests WHERE id=?", request);
        requests.replaceCurl(
                request,
                new CurlBo(
                        "curl 'http://127.0.0.1:" + port + "/unavailable' --max-time 1", version));
        String unavailable = manual(true);
        complete(unavailable);
        assertEquals("unknown", status(unavailable));
        assertEquals("unavailable", response(unavailable).state());
        assertNull(response(unavailable).body());
        assertEquals(2, hits.get());
    }

    @Test
    void partialAndOversizedBodiesArePersistedWithExplicitIncompleteStates() throws Exception {
        setup("/response-partial");
        requests.replaceCurl(
                request, new CurlBo("curl '" + url + "/response-partial' --max-time 1", 1));
        String partial = manual(false);
        complete(partial);
        assertEquals("unknown", status(partial));
        assertEquals("partial", response(partial).state());
        assertEquals("partial-response-only-secret", response(partial).body());
        requests.replaceCurl(request, new CurlBo("curl '" + url + "/response-large'", 2));
        String large = manual(true);
        complete(large);
        assertEquals("unknown", status(large));
        assertEquals("truncated", response(large).state());
        assertEquals(1048576, response(large).byteLength());
        assertEquals("z".repeat(1048576), response(large).body());
        assertEquals(1048576, response(large).limitBytes());
        assertEquals(2, hits.get());
    }

    @Test
    void responseDetailsDecodeDeclaredCharsetsAndPreserveBinaryBytes() throws Exception {
        setup("/response-gbk");
        String first = manual(false);
        complete(first);
        assertEquals("响应体中文", response(first).body());
        assertEquals("text", response(first).encoding());
        assertEquals("GBK", response(first).charset().toUpperCase(Locale.ROOT));
        requests.replaceCurl(request, new CurlBo("curl '" + url + "/response-binary'", 1));
        String binary = manual(true);
        complete(binary);
        assertEquals("base64", response(binary).encoding());
        assertArrayEquals(
                new byte[] {0, 1, 2, (byte) 255},
                Base64.getDecoder().decode(response(binary).body()));
        jdbc.update("DELETE FROM run_responses WHERE run_id=?", itemId(binary));
        assertEquals("not_recorded", response(binary).state());
        assertNull(response(binary).body());
    }

    @Test
    void plainResponseBytesAreDefensivelyCopiedAndNeedNoKey() throws Exception {
        setup("/response");
        String first = manual(false);
        complete(first);
        String second = manual(true);
        complete(second);
        var stored = responseMapper.selectById(itemId(first));
        byte[] bytes = stored.getBodyBytes();
        bytes[0] = 'x';
        assertEquals('{', stored.getBodyBytes()[0]);
        byte[] input = {1, 2};
        stored.setBodyBytes(input);
        input[0] = 9;
        assertEquals(1, stored.getBodyBytes()[0]);
        assertEquals(responseText(1), response(first).body());
        assertEquals(responseText(2), response(second).body());
        try (var files = Files.walk(DIR)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".key")));
        }
    }

    @Test
    void retentionRemovesPlainBodiesButKeepsIndependentDayMarkers() throws Exception {
        setup("/response");
        String batch = manual(false);
        complete(batch);
        jdbc.update(
                "UPDATE run_items SET finished_at=? WHERE id=?",
                clock.instant().minusSeconds(31 * 86400L).toString(),
                itemId(batch));
        scanner.retention();
        assertEquals(0, count("SELECT COUNT(*) FROM run_items"));
        assertEquals(0, count("SELECT COUNT(*) FROM run_responses"));
        assertEquals(
                1, count("SELECT COUNT(*) FROM daily_completions WHERE request_id=?", request));
        assertEquals(
                1, count("SELECT COUNT(*) FROM request_day_states WHERE request_id=?", request));
        String skipped = manual(false);
        complete(skipped);
        assertEquals("skipped", status(skipped));
        assertEquals(1, hits.get());
    }

    @Test
    void templatesArePlatformScopedValidatedVersionedAndCascadeWithTheirPlatform() {
        platform = addPlatform("模板平台", "", true);
        String other = addPlatform("另一个平台", "", true);
        var rules = new ResultRules(null, new ResultRules.Match("done", true, null), null, null);
        String id = addTemplate(platform, "  查询积分  ", rules);
        assertTrue(id.matches("[1-9][0-9]{0,18}"));
        assertTrue(Long.parseLong(id) > 0);
        assertEquals(List.of(), templates.queryList(other));
        var stored = templates.queryList(platform).getFirst();
        assertEquals("查询积分", stored.getName());
        assertEquals(rules, stored.getRules());
        assertEquals(1, stored.getVersion());
        assertFalse(Json.write(stored).contains("rulesJson"));
        templates.update(platform, id, new RequestTemplateBo("查询结果", ResultRules.defaults(), 1));
        assertEquals(2, templates.queryList(platform).getFirst().getVersion());
        assertEquals(
                409,
                assertThrows(
                                ApiException.class,
                                () ->
                                        templates.update(
                                                platform,
                                                id,
                                                new RequestTemplateBo("冲突", rules, 1)))
                        .status());
        assertEquals(
                404,
                assertThrows(
                                ApiException.class,
                                () ->
                                        templates.update(
                                                other, id, new RequestTemplateBo("错误平台", rules, 2)))
                        .status());
        assertEquals(
                404, assertThrows(ApiException.class, () -> templates.delete(other, id)).status());
        assertThrows(ApiException.class, () -> addTemplate(platform, " ", rules));
        assertThrows(ApiException.class, () -> addTemplate(platform, "名".repeat(61), rules));
        var invalid = new ResultRules(new ResultRules.Match("bad[*]", 0, null), null, null, null);
        assertThrows(ApiException.class, () -> addTemplate(platform, "错误规则", invalid));
        assertThrows(
                ApiException.class,
                () -> templates.update(platform, id, new RequestTemplateBo("错误规则", invalid, 2)));
        assertEquals("查询结果", templates.queryList(platform).getFirst().getName());
        String second = addTemplate(platform, "签到", null);
        templates.delete(platform, second);
        assertEquals(1, templates.queryList(platform).size());
        platforms.delete(platform);
        assertEquals(0, count("SELECT COUNT(*) FROM request_templates"));
    }

    @Test
    void templateChangesDoNotChangeRequestsOrQueuedRuleSnapshots() throws Exception {
        setup("/hold");
        String template = addTemplate(platform, "领取奖励", ResultRules.defaults());
        var copied = templates.queryList(platform).getFirst().getRules();
        String reward =
                addRequest(
                        account,
                        "领取奖励",
                        "curl '" + url + "/ok' --data-raw 'template-body'",
                        copied,
                        true);
        String batch =
                runs.manual(
                                new ManualRunBo(
                                        "platform", platform, false, "template-snapshot-fixture"))
                        .getFirst();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            var changed =
                    new ResultRules(new ResultRules.Match("code", 99, null), null, null, null);
            templates.update(platform, template, new RequestTemplateBo("新规则", changed, 1));
            templates.delete(platform, template);
            assertEquals(
                    copied,
                    Json.read(
                            text("SELECT rules_json FROM requests WHERE id=?", reward),
                            ResultRules.class));
            assertEquals(
                    copied,
                    Json.read(
                            text(
                                    "SELECT rules_json FROM run_items WHERE request_id=? AND"
                                            + " batch_id=?",
                                    reward,
                                    batch),
                            ResultRules.class));
            requests.update(reward, new RequestBo("领取奖励", true, changed, 1));
        } finally {
            release.countDown();
        }
        complete(batch);
        assertEquals(
                List.of("success", "success"),
                records.queryBatch(batch).getItems().stream().map(BatchItemVo::getStatus).toList());
        assertEquals(List.of("template-body"), bodies);
        assertEquals(2, hits.get());
    }

    @Test
    void snowflakeIdsAreStringsAcrossCatalogAndExecution() throws Exception {
        setup("/ok");
        String batch = manual(false);
        complete(batch);
        var batchRow = records.queryBatch(batch);
        var item = batchRow.getItems().getFirst();
        var ids = List.of(platform, account, request, batch, item.getId());
        assertEquals(ids.size(), new HashSet<>(ids).size());
        for (String id : ids) {
            assertTrue(id.matches("[1-9][0-9]{0,18}"));
            assertTrue(Long.parseLong(id) > 9_007_199_254_740_991L);
        }
        assertEquals(platform, mapper.selectById(platform).getId());
        assertEquals(platform, text("SELECT platform_id FROM accounts WHERE id=?", account));
        assertEquals(account, text("SELECT account_id FROM requests WHERE id=?", request));
        assertEquals(platform, batchRow.getPlatformId());
        assertEquals(batch, text("SELECT batch_id FROM run_items WHERE id=?", item.getId()));
        assertEquals(request, item.getRequestId());
        var json = Json.tree(Json.write(batchRow));
        assertTrue(json.path("id").isString());
        assertTrue(json.path("items").get(0).path("id").isString());
        assertEquals("success", status(batch));
        assertEquals(1, hits.get());
    }

    @Test
    void queueUsesPersistedProxyWithoutRestartAndRejectsInvalidSettings() throws Exception {
        setup("/ok");
        requests.replaceCurl(
                request, new CurlBo("curl 'http://127.0.0.1:1/ok' --data-raw 'proxy-body'", 1));
        var previous = settings.query();
        var proxy = new ProxySettings("http", "127.0.0.1", server.getAddress().getPort());
        settings.save(new SettingsBo(true, 2, 3, 30, previous.version(), proxy));
        assertEquals(proxy, settings.query().proxy());
        var saved = settings.query();
        assertThrows(
                ApiException.class,
                () ->
                        settings.save(
                                new SettingsBo(
                                        true,
                                        2,
                                        3,
                                        30,
                                        saved.version(),
                                        new ProxySettings(
                                                "http", "http://user:secret@proxy", 80))));
        assertThrows(
                ApiException.class,
                () ->
                        settings.save(
                                new SettingsBo(
                                        true,
                                        2,
                                        3,
                                        30,
                                        saved.version(),
                                        new ProxySettings("http", "localhost", 0))));
        assertThrows(
                ApiException.class,
                () ->
                        settings.save(
                                new SettingsBo(
                                        true,
                                        2,
                                        3,
                                        30,
                                        saved.version(),
                                        new ProxySettings("other", "localhost", 80))));
        assertEquals(saved, settings.query());
        String batch = manual(false);
        complete(batch);
        assertEquals("success", status(batch));
        assertEquals(List.of("proxy-body"), bodies);
        assertEquals(1, hits.get());
        assertThrows(
                ApiException.class,
                () ->
                        settings.save(
                                new SettingsBo(
                                        true,
                                        2,
                                        3,
                                        30,
                                        previous.version(),
                                        ProxySettings.system())));
        assertEquals(proxy, settings.query().proxy());
    }

    @Test
    void missingNewBackupFieldsAreRejectedWithoutReplacingData() {
        setup("/ok");
        String template = addTemplate(platform, "不能被删除的模板", ResultRules.defaults());
        var exported = config(false);
        for (String field : List.of("proxy", "templates")) {
            var damaged = Json.map(Json.write(exported));
            var payload = (Map<String, Object>) damaged.get("payload");
            if (field.equals("proxy"))
                ((Map<String, Object>) payload.get("settings")).remove(field);
            else payload.remove(field);
            assertThrows(ApiException.class, () -> restore(decodeMap(damaged)));
            assertEquals(template, templates.queryList(platform).getFirst().getId());
        }
        assertTrue(requests.queryRevision(request, 1).rawCurl().contains("test-only-credential"));
    }

    @Test
    void actualSqliteRequestsArePlainAndQueuedVersionIsFrozen() throws Exception {
        setup("/ok");
        String batch = manual(false);
        requests.replaceCurl(
                request, new CurlBo("curl '" + url + "/ok' --data-raw 'second-body'", 1));
        complete(batch);
        assertEquals("success", status(batch));
        assertEquals(List.of("first-body"), bodies);
        assertEquals(1, records.queryBatch(batch).getItems().getFirst().getRequestRevision());
        assertEquals(2, integer("SELECT current_revision FROM requests WHERE id=?", request));
        assertTrue(
                text(
                                "SELECT raw_curl FROM request_revisions WHERE request_id=? AND"
                                        + " revision=1",
                                request)
                        .contains("test-only-credential"));
        var spec =
                Json.read(
                        text(
                                "SELECT spec_json FROM request_revisions WHERE request_id=? AND"
                                        + " revision=1",
                                request),
                        RequestSpec.class);
        assertEquals("first-body", new String(spec.bodyBytes(), StandardCharsets.UTF_8));
        assertEquals(
                "second-body",
                new String(
                        requests.queryRevision(request, null).spec().bodyBytes(),
                        StandardCharsets.UTF_8));
    }

    @Test
    void manualIdempotencyAndDailyMarkersSurviveLogCleanup() throws Exception {
        setup("/ok");
        var command = new ManualRunBo("request", request, false, UUID.randomUUID().toString());
        var ids = runs.manual(command);
        assertEquals(ids, runs.manual(command));
        complete(ids.getFirst());
        assertEquals(1, hits.get());
        jdbc.update("DELETE FROM run_items");
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
        requests.replaceCurl(request, new CurlBo("curl '" + url + "/ok'", 1));
        release.countDown();
        complete(batch);
        assertEquals("expired", status(batch));
        assertEquals(0, integer("SELECT auth_paused FROM requests WHERE id=?", request));
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
        requests.replaceCurl(
                request,
                new CurlBo(
                        "curl '" + url + "/expired'",
                        integer("SELECT version FROM requests WHERE id=?", request)));
        String force = manual(true);
        complete(force);
        assertEquals("expired", status(force));
        assertEquals(1, integer("SELECT auth_paused FROM requests WHERE id=?", request));
        requests.replaceCurl(
                request,
                new CurlBo(
                        "curl '" + url + "/ok'",
                        integer("SELECT version FROM requests WHERE id=?", request)));
        assertEquals(0, integer("SELECT auth_paused FROM requests WHERE id=?", request));
        String last = manual(true);
        complete(last);
        assertEquals("success", status(last));
    }

    SchedulePlan enableAutomatic(List<String> times, int catchupMinutes) {
        var s = schedules.queryById(platform);
        schedules.save(
                platform,
                new ScheduleBo(
                        true,
                        "daily",
                        s.weekdays(),
                        times,
                        s.timezone(),
                        0,
                        catchupMinutes,
                        true,
                        s.revision()));
        var global = settings.query();
        settings.save(
                new SettingsBo(
                        false,
                        global.concurrency(),
                        global.timeoutSeconds(),
                        global.retentionDays(),
                        global.version(),
                        global.proxy()));
        return new SchedulePlan(
                platform,
                schedules.queryById(platform).toSpec(),
                Instant.parse(
                        text(
                                "SELECT effective_from FROM platform_schedules WHERE platform_id=?",
                                platform)));
    }

    @Test
    void sameOccurrenceDeduplicatesAcrossScheduleRevisionAndLogCleanup() throws Exception {
        setup("/ok");
        Instant at = clock.instant();
        var plan = enableAutomatic(List.of("08:00"), 120);
        runs.automatic(plan, at, coordinator.scanGeneration().orElseThrow());
        await(() -> records.active().isEmpty());
        jdbc.update("DELETE FROM run_items");
        var edited = enableAutomatic(List.of("08:00", "09:00"), 120);
        runs.automatic(edited, at, coordinator.scanGeneration().orElseThrow());
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM run_batches WHERE platform_id=? AND source='auto'",
                        platform));
        assertEquals(1, hits.get());
    }

    @Test
    void cancelsPendingAndRefusesDeletingActiveRequests() throws Exception {
        setup("/hold");
        addRequest(account, "第二请求", "curl '" + url + "/ok'", ResultRules.defaults(), true);
        String batch =
                runs.manual(
                                new ManualRunBo(
                                        "platform", platform, false, UUID.randomUUID().toString()))
                        .getFirst();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(ApiException.class, () -> platforms.delete(platform));
        assertThrows(ApiException.class, () -> accounts.delete(account));
        assertThrows(ApiException.class, () -> requests.delete(request));
        runs.cancel(batch);
        release.countDown();
        complete(batch);
        var items = records.queryBatch(batch).getItems();
        assertEquals("success", items.get(0).getStatus());
        assertEquals("cancelled", items.get(1).getStatus());
        assertEquals(1, hits.get());
    }

    @Test
    void restartRecoveryMarksRunningUnknownWithoutReplay() throws Exception {
        setup("/ok");
        String batch = manual(false);
        complete(batch);
        jdbc.update("UPDATE run_items SET status='running' WHERE batch_id=?", batch);
        jdbc.update("UPDATE run_batches SET status='running' WHERE id=?", batch);
        runs.recover();
        assertEquals("unknown", status(batch));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM request_day_states WHERE request_id=? AND"
                                + " unknown_pending=1",
                        request));
        await(() -> records.active().isEmpty());
        assertEquals(1, hits.get());
        String skipped = manual(false);
        complete(skipped);
        assertEquals("skipped", status(skipped));
        assertEquals(1, hits.get());
    }

    @Test
    void fullPlainBackupRoundTripRestoresCurrentCurlTemplatesSettingsAndMarkers() throws Exception {
        setup("/ok");
        String template = addTemplate(platform, "签到模板", ResultRules.defaults());
        String batch = manual(false);
        complete(batch);
        requests.replaceCurl(
                request,
                new CurlBo("curl '" + url + "/ok' -H 'Cookie: test-only-credential=beta'", 1));
        var previous = settings.query();
        var proxy = new ProxySettings("http", "127.0.0.1", 12345);
        settings.save(new SettingsBo(true, 3, 7, 45, previous.version(), proxy));
        var exported = config(true);
        assertEquals("signdesk-plain-v1", exported.format());
        assertTrue(Json.write(exported).contains("test-only-credential=beta"));
        assertFalse(Json.write(exported).contains("response-only-fixture-secret"));
        templates.update(
                platform, template, new RequestTemplateBo("导出后修改", ResultRules.defaults(), 1));
        await(
                () -> {
                    try {
                        runs.beginMaintenance();
                        runs.endMaintenance();
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                });
        Instant importTime = clock.instant();
        restore(exported);
        assertTrue(
                requests.queryRevision(request, 1).rawCurl().contains("test-only-credential=beta"));
        assertEquals(1, count("SELECT COUNT(*) FROM request_revisions"));
        assertTrue(settings.query().paused());
        assertEquals(3, settings.query().concurrency());
        assertEquals(7, settings.query().timeoutSeconds());
        assertEquals(45, settings.query().retentionDays());
        assertEquals(proxy, settings.query().proxy());
        assertEquals(template, templates.queryList(platform).getFirst().getId());
        assertEquals("签到模板", templates.queryList(platform).getFirst().getName());
        assertEquals(ResultRules.defaults(), templates.queryList(platform).getFirst().getRules());
        assertEquals(
                1, count("SELECT COUNT(*) FROM daily_completions WHERE request_id=?", request));
        assertEquals(0, count("SELECT COUNT(*) FROM run_items"));
        assertEquals(0, count("SELECT COUNT(*) FROM run_batches"));
        assertEquals(1, integer("SELECT current_revision FROM requests WHERE id=?", request));
        assertEquals(
                importTime.toString(),
                text(
                        "SELECT effective_from FROM platform_schedules WHERE platform_id=?",
                        platform));
        assertEquals(1, hits.get(), "import must not send requests");
    }

    @Test
    void invalidTemplateBackupsAreRejectedBeforeReplacingData() {
        setup("/ok");
        String template = addTemplate(platform, "有效模板", ResultRules.defaults());
        var exported = config(false);
        var invalidRules =
                new ResultRules(new ResultRules.Match("bad[*]", 0, null), null, null, null);
        for (var change :
                List.of(
                        Map.of("platformId", "1"),
                        Map.of("id", "9223372036854775808"),
                        Map.of("id", "0"),
                        Map.of("id", "abcdef0123456789abcdef0123456789"),
                        Map.of("name", " "),
                        Map.of("rules", invalidRules))) {
            var damaged = Json.map(Json.write(exported));
            var payload = (Map<String, Object>) damaged.get("payload");
            var list = (List<Map<String, Object>>) payload.get("templates");
            list.getFirst().putAll(change);
            assertThrows(ApiException.class, () -> restore(decodeMap(damaged)));
            assertEquals(template, templates.queryList(platform).getFirst().getId());
            assertEquals(1, count("SELECT COUNT(*) FROM requests"));
            assertTrue(
                    requests.queryRevision(request, 1).rawCurl().contains("test-only-credential"));
        }
        var duplicate = Json.map(Json.write(exported));
        var list =
                (List<Map<String, Object>>)
                        ((Map<String, Object>) duplicate.get("payload")).get("templates");
        list.add(list.getFirst());
        assertThrows(ApiException.class, () -> restore(decodeMap(duplicate)));
        assertEquals(1, templates.queryList(platform).size());
    }

    @Test
    void configExportContainsNoRequestAndRestoreDisablesPlaceholders() {
        setup("/ok");
        String template = addTemplate(platform, "积分模板", ResultRules.defaults());
        var exported = config(false);
        String text = Json.write(exported);
        assertFalse(text.contains("test-only-credential"));
        assertFalse(text.contains("first-body"));
        assertFalse(text.contains(url));
        assertFalse(text.contains("rawCurl"));
        assertTrue(exported.payload().completed().isEmpty());
        assertTrue(exported.payload().pending().isEmpty());
        templates.delete(platform, template);
        restore(exported);
        assertEquals(template, templates.queryList(platform).getFirst().getId());
        assertEquals(ResultRules.defaults(), templates.queryList(platform).getFirst().getRules());
        assertEquals(0, count("SELECT COUNT(*) FROM request_revisions"));
        assertEquals(0, integer("SELECT enabled FROM requests WHERE id=?", request));
        assertEquals(1, integer("SELECT auth_paused FROM requests WHERE id=?", request));
    }

    @Test
    void singleInstanceAndPlainStorageNeedNoKeyOrExternalSecret() throws Exception {
        setup("/ok");
        assertThrows(IllegalStateException.class, () -> new InstanceLock(lock.directory()));
        assertTrue(requests.queryRevision(request, 1).rawCurl().contains("test-only-credential"));
        try (var paths = Files.walk(DIR)) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().endsWith(".key")));
        }
        assertEquals(
                409,
                assertThrows(
                                ApiException.class,
                                () -> updatePlatform(platform, "其他名称", "", true, 99))
                        .status());
    }

    @Test
    void exactOneMiBResponseIsCompleteRatherThanTruncated() throws Exception {
        setup("/response-exact");
        String batch = manual(false);
        complete(batch);
        assertEquals("unknown", status(batch));
        assertEquals("complete", response(batch).state());
        assertEquals(1048576, response(batch).byteLength());
        assertEquals("e".repeat(1048576), response(batch).body());
    }

    @Test
    void proxySwitchesClearPersistedHostAndPortIncludingFreshMapperReads() {
        setup("/ok");
        var old = settings.query();
        settings.save(
                new SettingsBo(
                        true,
                        2,
                        20,
                        30,
                        old.version(),
                        new ProxySettings("http", "127.0.0.1", 12345)));
        for (var proxy : List.of(new ProxySettings("direct", "", 0), ProxySettings.system())) {
            var current = settings.query();
            settings.save(new SettingsBo(true, 2, 20, 30, current.version(), proxy));
            assertEquals(proxy, settings.query().proxy());
            assertEquals("", text("SELECT proxy_host FROM settings"));
            assertEquals(0, integer("SELECT proxy_port FROM settings"));
            assertEquals(proxy.mode(), text("SELECT proxy_mode FROM settings"));
        }
    }

    @Test
    void forceCannotBypassDisabledParentsOrCredentialPause() throws Exception {
        setup("/ok");
        jdbc.update("UPDATE requests SET auth_paused=1 WHERE id=?", request);
        String paused = manual(true);
        complete(paused);
        assertEquals("skipped", status(paused));
        assertEquals(0, hits.get());
        requests.replaceCurl(request, new CurlBo("curl '" + url + "/ok'", 1));
        accounts.update(account, new AccountBo("停用账号", false, 1));
        assertEquals(409, assertThrows(ApiException.class, () -> manual(true)).status());
        accounts.update(account, new AccountBo("启用账号", true, 2));
        updatePlatform(platform, "停用平台", "", false, 1);
        assertEquals(409, assertThrows(ApiException.class, () -> manual(true)).status());
        assertEquals(0, hits.get());
    }

    @Test
    void queuedItemsRecheckEnablementRatherThanTrustingTheFrozenBatch() throws Exception {
        setup("/hold");
        String second =
                addRequest(account, "稍后停用", "curl '" + url + "/ok'", ResultRules.defaults(), true);
        String batch =
                runs.manual(new ManualRunBo("platform", platform, true, "queued-gate-fixture"))
                        .getFirst();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        requests.update(second, new RequestBo("稍后停用", false, ResultRules.defaults(), 1));
        release.countDown();
        complete(batch);
        assertEquals(
                List.of("success", "skipped"),
                records.queryBatch(batch).getItems().stream().map(BatchItemVo::getStatus).toList());
        assertEquals(1, hits.get());
    }

    @Test
    void samePlatformIsSerialAndCrossPlatformConcurrencyIsBoundedWithoutHoldingTransactions()
            throws Exception {
        AtomicInteger concurrent = new AtomicInteger(), max = new AtomicInteger();
        CountDownLatch twoEntered = new CountDownLatch(2), unblock = new CountDownLatch(1);
        server.createContext(
                "/parallel",
                e -> {
                    hits.incrementAndGet();
                    int active = concurrent.incrementAndGet();
                    max.accumulateAndGet(active, Math::max);
                    twoEntered.countDown();
                    try {
                        unblock.await(10, TimeUnit.SECONDS);
                        byte[] reply = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, reply.length);
                        e.getResponseBody().write(reply);
                    } catch (Exception ignored) {
                    } finally {
                        concurrent.decrementAndGet();
                        e.close();
                    }
                });
        try {
            for (int i = 0; i < 3; i++) {
                String p = addPlatform("并发平台" + i, "", true), a = addAccount(p, "账号", true);
                addRequest(a, "请求", "curl '" + url + "/parallel'", ResultRules.defaults(), true);
            }
            var batches =
                    runs.manual(new ManualRunBo("all", null, false, "bounded-concurrency-fixture"));
            assertEquals(3, batches.size());
            assertTrue(twoEntered.await(5, TimeUnit.SECONDS));
            assertEquals(2, concurrent.get());
            assertEquals(2, integer("SELECT COUNT(*) FROM run_items WHERE status='running'"));
            assertEquals(1, integer("SELECT COUNT(*) FROM run_items WHERE status='queued'"));
            assertTimeout(
                    Duration.ofSeconds(2),
                    () -> {
                        var v = settings.query();
                        settings.save(
                                new SettingsBo(
                                        true, 2, 20, 30, v.version(), ProxySettings.system()));
                    });
            unblock.countDown();
            for (String batch : batches) complete(batch);
            assertEquals(2, max.get());
            assertEquals(3, hits.get());
        } finally {
            unblock.countDown();
            server.removeContext("/parallel");
        }
    }

    @Test
    void samePlatformRequestsNeverOverlapEvenWithTwoAvailablePlatformSlots() throws Exception {
        setup("/hold");
        addRequest(account, "第二请求", "curl '" + url + "/ok'", ResultRules.defaults(), true);
        String batch =
                runs.manual(new ManualRunBo("platform", platform, false, "serial-platform-fixture"))
                        .getFirst();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, hits.get());
        assertEquals(1, integer("SELECT COUNT(*) FROM run_items WHERE status='running'"));
        assertEquals(1, integer("SELECT COUNT(*) FROM run_items WHERE status='queued'"));
        assertEquals(
                409,
                assertThrows(
                                ApiException.class,
                                () ->
                                        runs.manual(
                                                new ManualRunBo(
                                                        "request",
                                                        request,
                                                        true,
                                                        "same-request-active-fixture")))
                        .status());
        release.countDown();
        complete(batch);
        assertEquals(2, hits.get());
    }

    @Test
    void globalRoutingIsReadAtSendStartWhileRevisionAndIntervalRemainFrozen() throws Exception {
        setup("/hold");
        String second =
                addRequest(
                        account,
                        "代理后发",
                        "curl 'http://127.0.0.1:1/ok' --data-raw 'frozen-proxy-body'",
                        ResultRules.defaults(),
                        true);
        var plan = schedules.queryById(platform);
        schedules.save(
                platform,
                new ScheduleBo(
                        false,
                        "daily",
                        plan.weekdays(),
                        plan.times(),
                        plan.timezone(),
                        2,
                        120,
                        true,
                        plan.revision()));
        String batch =
                runs.manual(
                                new ManualRunBo(
                                        "platform",
                                        platform,
                                        false,
                                        "global-send-settings-fixture"))
                        .getFirst();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            var current = settings.query();
            settings.save(
                    new SettingsBo(
                            true,
                            2,
                            1,
                            30,
                            current.version(),
                            new ProxySettings("http", "127.0.0.1", server.getAddress().getPort())));
            requests.replaceCurl(
                    second,
                    new CurlBo("curl '" + url + "/response' --data-raw 'new-not-frozen'", 1));
            plan = schedules.queryById(platform);
            schedules.save(
                    platform,
                    new ScheduleBo(
                            false,
                            "daily",
                            plan.weekdays(),
                            plan.times(),
                            plan.timezone(),
                            0,
                            120,
                            true,
                            plan.revision()));
            Thread.sleep(
                    1100); // The already-sending first request keeps its original 20-second limit.
            long released = System.nanoTime();
            release.countDown();
            await(
                    () ->
                            records.queryBatch(batch)
                                    .getItems()
                                    .getFirst()
                                    .getStatus()
                                    .equals("success"));
            assertTimeout(
                    Duration.ofSeconds(1), () -> updatePlatform(platform, "间隔期间可编辑", "", true, 1));
            complete(batch);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - released) >= 1800);
            assertEquals(
                    List.of("success", "success"),
                    records.queryBatch(batch).getItems().stream()
                            .map(BatchItemVo::getStatus)
                            .toList());
            assertEquals(List.of("frozen-proxy-body"), bodies);
            assertEquals(2, hits.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void maintenanceAndNewEnqueueAreMutuallyExclusiveAndActiveImportIsRejected() throws Exception {
        setup("/hold");
        var snapshot = config(true);
        var plan =
                new SchedulePlan(
                        platform,
                        schedules.queryById(platform).toSpec(),
                        Instant.parse(
                                text(
                                        "SELECT effective_from FROM platform_schedules WHERE"
                                            + " platform_id=?",
                                        platform)));
        long generation = coordinator.scanGeneration().orElseThrow();
        runs.beginMaintenance();
        try {
            assertEquals(409, assertThrows(ApiException.class, () -> manual(false)).status());
            runs.automatic(plan, clock.instant(), generation);
            assertEquals(0, count("SELECT COUNT(*) FROM run_batches"));
            assertThrows(ApiException.class, () -> restore(snapshot));
        } finally {
            runs.endMaintenance();
        }
        String batch = manual(false);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(409, assertThrows(ApiException.class, () -> restore(snapshot)).status());
        assertEquals(1, count("SELECT COUNT(*) FROM requests"));
        release.countDown();
        complete(batch);
        assertEquals(1, hits.get());
    }

    @Test
    void failedResultPersistenceRollsBackMarkersAndNeverRetriesHttp() throws Exception {
        setup("/response");
        jdbc.execute(
                "CREATE TRIGGER fail_response_fixture BEFORE INSERT ON run_responses BEGIN SELECT"
                        + " RAISE(ABORT,'fixture write rejected'); END");
        String batch;
        try {
            batch = manual(false);
            complete(batch);
            assertEquals("unknown", status(batch));
            assertEquals(1, hits.get());
            assertEquals(0, count("SELECT COUNT(*) FROM run_responses"));
            assertEquals(0, count("SELECT COUNT(*) FROM daily_completions"));
            assertEquals(
                    1, count("SELECT COUNT(*) FROM request_day_states WHERE unknown_pending=1"));
            assertEquals("not_recorded", response(batch).state());
        } finally {
            jdbc.execute("DROP TRIGGER fail_response_fixture");
        }
        String skipped = manual(false);
        complete(skipped);
        assertEquals("skipped", status(skipped));
        assertEquals(1, hits.get());
        String forced = manual(true);
        complete(forced);
        assertEquals("success", status(forced));
        assertEquals(2, hits.get());
        assertEquals(1, count("SELECT COUNT(*) FROM run_responses"));
    }

    @Test
    void replaceFailureAfterDeletionRollsBackAllDataAndReleasesMaintenance() throws Exception {
        setup("/ok");
        String template = addTemplate(platform, "保留模板", ResultRules.defaults());
        var exported = Json.map(Json.write(config(true)));
        var ps =
                (List<Map<String, Object>>)
                        ((Map<String, Object>) exported.get("payload")).get("platforms");
        ps.getFirst().put("name", "trigger-fixture");
        var damaged = decodeMap(exported);
        var before = settings.query();
        jdbc.execute(
                "CREATE TRIGGER fail_import_fixture BEFORE INSERT ON platforms WHEN"
                        + " NEW.name='trigger-fixture' BEGIN SELECT RAISE(ABORT,'fixture import"
                        + " rejected'); END");
        try {
            assertThrows(RuntimeException.class, () -> restore(damaged));
            assertEquals("测试平台", mapper.selectById(platform).getName());
            assertEquals(template, templates.queryList(platform).getFirst().getId());
            assertEquals(before, settings.query());
            assertTrue(
                    requests.queryRevision(request, 1).rawCurl().contains("test-only-credential"));
        } finally {
            jdbc.execute("DROP TRIGGER fail_import_fixture");
        }
        String batch = manual(false);
        complete(batch);
        assertEquals("success", status(batch));
    }

    @Test
    void fullBackupRestoresPendingMarkerButNeverResponsesOrAnActiveQueue() throws Exception {
        setup("/unmatched");
        String batch = manual(false);
        complete(batch);
        var exported = config(true);
        assertEquals(1, exported.payload().pending().size());
        assertTrue(exported.payload().completed().isEmpty());
        await(
                () -> {
                    try {
                        runs.beginMaintenance();
                        runs.endMaintenance();
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                });
        restore(exported);
        assertEquals(1, count("SELECT COUNT(*) FROM request_day_states WHERE unknown_pending=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM run_responses"));
        assertEquals(0, count("SELECT COUNT(*) FROM run_batches"));
        String skipped = manual(false);
        complete(skipped);
        assertEquals("skipped", status(skipped));
        assertEquals(1, hits.get());
    }

    @Test
    void configurationPlaceholdersStayDisabledUntilCurlAndEnablementAreExplicitlyUpdated()
            throws Exception {
        setup("/ok");
        restore(config(false));
        assertThrows(ApiException.class, () -> manual(true));
        requests.replaceCurl(request, new CurlBo("curl '" + url + "/ok'", 1));
        assertEquals(0, integer("SELECT enabled FROM requests WHERE id=?", request));
        assertEquals(0, integer("SELECT auth_paused FROM requests WHERE id=?", request));
        requests.update(request, new RequestBo("每日签到", true, ResultRules.defaults(), 2));
        String batch = manual(false);
        complete(batch);
        assertEquals("success", status(batch));
    }

    @Test
    void automaticGatesUseInjectedClockForExpiredWindowAndBusinessDay() throws Exception {
        setup("/ok");
        var plan = enableAutomatic(List.of("08:00"), 120);
        // Keep dispatch outside this short monitor block so the batch is valid when committed,
        // then deterministically age it before prepare. The HTTP gate is still real SQLite.
        synchronized (coordinator) {
            runs.automatic(plan, clock.instant(), coordinator.scanGeneration().orElseThrow());
            testClock.set(clock.instant().plusSeconds(3 * 3600));
        }
        await(() -> records.active().isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM run_items WHERE status='skipped'"));
        assertEquals(0, hits.get());
        testClock.set(Instant.parse("2026-10-09T15:59:00Z"));
        var dayBoundary = enableAutomatic(List.of("23:59"), 1440);
        synchronized (coordinator) {
            runs.automatic(
                    dayBoundary, clock.instant(), coordinator.scanGeneration().orElseThrow());
            // Only two minutes elapse, well inside the frozen 24-hour window, but the business
            // date changes in Asia/Shanghai. The persisted batch must still be skipped.
            testClock.set(clock.instant().plusSeconds(120));
        }
        await(() -> records.active().isEmpty());
        assertEquals(2, count("SELECT COUNT(*) FROM run_items WHERE status='skipped'"));
        assertEquals(0, hits.get());
    }

    @Test
    void scannerHonorsEffectiveFromAndCoalescesMissedSlotsToLatestUtcInstant() throws Exception {
        testClock.set(Instant.parse("2026-10-09T23:00:00Z"));
        setup("/ok");
        var s = schedules.queryById(platform);
        schedules.save(
                platform,
                new ScheduleBo(
                        true,
                        "daily",
                        s.weekdays(),
                        List.of("08:00", "08:30", "09:00"),
                        "Asia/Shanghai",
                        0,
                        180,
                        true,
                        s.revision()));
        testClock.set(Instant.parse("2026-10-10T01:05:00Z"));
        var current = settings.query();
        settings.save(new SettingsBo(false, 2, 20, 30, current.version(), ProxySettings.system()));
        scanner.scan();
        await(() -> records.active().isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM run_batches"));
        assertEquals("2026-10-10T01:00:00Z", text("SELECT scheduled_at FROM run_batches"));
        assertEquals("2026-10-10", text("SELECT business_date FROM run_batches"));
        assertEquals(1, hits.get());
        scanner.scan();
        assertEquals(1, count("SELECT COUNT(*) FROM run_batches"));
        var plan = schedules.queryById(platform);
        schedules.save(
                platform,
                new ScheduleBo(
                        true,
                        "daily",
                        plan.weekdays(),
                        plan.times(),
                        "Asia/Shanghai",
                        0,
                        180,
                        true,
                        plan.revision()));
        scanner.scan();
        assertEquals(1, count("SELECT COUNT(*) FROM run_batches"));
    }

    @Test
    void dashboardUsesEachPlatformBusinessDateWithoutExposingRequestSnapshots() {
        testClock.set(Instant.parse("2026-10-08T16:05:00Z"));
        setup("/ok");
        String utc = addPlatform("UTC平台", "", true),
                a = addAccount(utc, "UTC账号", true),
                r = addRequest(a, "UTC请求", "curl '" + url + "/ok'", ResultRules.defaults(), true);
        var plan = schedules.queryById(utc);
        schedules.save(
                utc,
                new ScheduleBo(
                        false,
                        "daily",
                        plan.weekdays(),
                        plan.times(),
                        "UTC",
                        0,
                        120,
                        true,
                        plan.revision()));
        jdbc.update(
                "INSERT INTO daily_completions VALUES(?,?,?)", request, "2026-10-09", Ids.next());
        jdbc.update("INSERT INTO daily_completions VALUES(?,?,?)", r, "2026-10-08", Ids.next());
        jdbc.update("INSERT INTO request_day_states VALUES(?,?,1)", r, "2026-10-08");
        var dashboard = system.dashboard();
        assertEquals("2026-10-09", dashboard.date());
        assertEquals(2, dashboard.completed());
        assertEquals(1, dashboard.needsAttention());
        assertEquals(2, dashboard.platforms());
        assertEquals(2, dashboard.requests());
        String json = Json.write(dashboard);
        assertFalse(json.contains("test-only-credential"));
        assertFalse(json.contains("rawCurl"));
    }
}
