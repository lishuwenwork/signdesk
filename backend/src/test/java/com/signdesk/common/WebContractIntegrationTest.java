package com.signdesk.common;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.domain.bo.*;
import com.signdesk.engine.ResultRules;
import com.signdesk.service.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebContractIntegrationTest {
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("signdesk-web-contract-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signdesk.data-dir", () -> DIR.resolve("data").toString());
        registry.add("signdesk.allowed-hosts", () -> "127.0.0.1");
    }

    @Autowired Environment environment;
    @Autowired IPlatformService platforms;
    @Autowired IAccountService accounts;
    @Autowired IRequestService requests;
    @Autowired IRequestTemplateService templates;
    @Autowired ISettingsService settings;
    final HttpClient client = HttpClient.newHttpClient();

    String addPlatform(String name, String note, boolean enabled) {
        var b = new PlatformBo();
        b.setName(name);
        b.setNote(note);
        b.setEnabled(enabled);
        return platforms.insert(b);
    }

    String addAccount(String platform, String alias, boolean enabled) {
        return accounts.insert(platform, new NewAccountBo(alias, enabled));
    }

    String addRequest(
            String account, String name, String curl, ResultRules rules, boolean enabled) {
        return requests.insert(account, new NewRequestBo(name, curl, enabled, rules));
    }

    @Test
    void platformBoAndActionVosKeepTheHttpContractAndPagingBounds() throws Exception {
        var invalid = post("/platforms", Map.of("name", " ", "enabled", true));
        assertEquals(400, invalid.statusCode());
        var created = post("/platforms", Map.of("name", "P1接口测试", "note", "", "enabled", true));
        assertEquals(200, created.statusCode());
        assertEquals(java.util.Set.of("id"), Json.map(created.body()).keySet());
        String id = Json.tree(created.body()).path("id").asString();
        assertTrue(id.matches("[1-9][0-9]*"));
        try {
            var input = Map.of("name", "P1修改", "note", "", "enabled", false, "version", 1);
            var saved = call("PUT", "/platforms/" + id, input);
            assertEquals(200, saved.statusCode());
            assertEquals(Map.of("saved", true), Json.map(saved.body()));
            assertEquals(409, call("PUT", "/platforms/" + id, input).statusCode());
            var page = call("GET", "/runs?page=0&size=200", null);
            assertEquals(200, page.statusCode());
            assertEquals(
                    java.util.Set.of("items", "total", "page", "size"),
                    Json.map(page.body()).keySet());
            assertEquals(1, Json.tree(page.body()).path("page").asInt());
            assertEquals(100, Json.tree(page.body()).path("size").asInt());
            var lastPage = call("GET", "/runs?page=2147483647&size=100", null);
            assertEquals(200, lastPage.statusCode());
            assertTrue(Json.tree(lastPage.body()).path("items").isEmpty());
        } finally {
            var deleted = call("DELETE", "/platforms/" + id, null);
            assertEquals(200, deleted.statusCode());
            assertEquals(Map.of("deleted", true), Json.map(deleted.body()));
        }
    }

    @Test
    void settingsApiPersistsProxyAndValidatesBeforeWriting() throws Exception {
        var current = settings.query();
        var uri =
                URI.create(
                        "http://127.0.0.1:"
                                + environment.getProperty("local.server.port")
                                + "/api/settings");
        var payload = Json.map(Json.write(current));
        payload.put("proxy", Map.of("mode", "http", "host", "127.0.0.1", "port", 12345));
        var response =
                client.send(
                        HttpRequest.newBuilder(uri)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(payload)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var fetched =
                client.send(
                        HttpRequest.newBuilder(uri).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(12345, Json.tree(fetched.body()).path("proxy").path("port").asInt());
        payload.put("version", settings.query().version());
        payload.put("proxy", Map.of("mode", "http", "host", "127.0.0.1", "port", 65536));
        var invalid =
                client.send(
                        HttpRequest.newBuilder(uri)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(payload)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalid.statusCode());
        assertEquals(12345, settings.query().proxy().port());
        var saved = settings.query();
        settings.save(
                new SettingsBo(
                        current.paused(),
                        current.concurrency(),
                        current.timeoutSeconds(),
                        current.retentionDays(),
                        saved.version(),
                        current.proxy()));
    }

    HttpResponse<String> post(String path, Object body) throws Exception {
        return call("POST", path, body);
    }

    HttpResponse<String> call(String method, String path, Object body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + environment.getProperty("local.server.port")
                                                + "/api"
                                                + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void executionDetailsReturnTheResponseBodyButListsAndBatchesDoNot() throws Exception {
        var fixture = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String text = "{\n\"code\":0,\"message\":\"响应体 api-response-only-secret\"\n}";
        fixture.createContext(
                "/reply",
                e -> {
                    byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        fixture.start();
        try {
            String platform = addPlatform("响应详情接口测试", "", true);
            String account = addAccount(platform, "本地账号", true);
            String request =
                    addRequest(
                            account,
                            "响应记录",
                            "curl 'http://127.0.0.1:" + fixture.getAddress().getPort() + "/reply'",
                            ResultRules.defaults(),
                            true);
            var accepted =
                    post(
                            "/runs",
                            new ManualRunBo("request", request, false, "response-api-fixture"));
            assertEquals(202, accepted.statusCode());
            String batch = Json.tree(accepted.body()).path("batchIds").get(0).asString();
            String item = null;
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < until) {
                var current = call("GET", "/batches/" + batch, null);
                assertFalse(current.body().contains("api-response-only-secret"));
                var json = Json.tree(current.body());
                if (json.path("status").asString().equals("completed")) {
                    item = json.path("items").get(0).path("id").asString();
                    break;
                }
                Thread.sleep(30);
            }
            assertNotNull(item, "execution did not finish");
            var detail = call("GET", "/runs/" + item, null);
            assertEquals(200, detail.statusCode());
            assertEquals("no-store", detail.headers().firstValue("Cache-Control").orElseThrow());
            var response = Json.tree(detail.body()).path("response");
            assertEquals("complete", response.path("state").asString());
            assertEquals("text", response.path("encoding").asString());
            assertEquals(text, response.path("body").asString());
            assertEquals(
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    response.path("byteLength").asInt());
            var logs = call("GET", "/runs", null);
            assertFalse(logs.body().contains("api-response-only-secret"));
            for (var entry : Json.tree(logs.body()).path("items"))
                assertTrue(entry.path("response").isMissingNode());
        } finally {
            fixture.stop(0);
        }
    }

    @Test
    void templateApiValidatesScopeAndVersionsWithoutExposingRequestContent() throws Exception {
        String platform = addPlatform("接口模板测试", "", true);
        String other = addPlatform("隔离平台", "", true);
        String path = "/platforms/" + platform + "/templates";
        var rules = new ResultRules(null, new ResultRules.Match("done", true, null), null, null);
        var created = post(path, Map.of("name", "领取奖励", "rules", rules));
        assertEquals(200, created.statusCode(), created.body());
        var id = Json.tree(created.body()).path("id");
        assertTrue(id.isString());
        assertTrue(id.asString().matches("[1-9][0-9]{0,18}"));
        var listed = call("GET", path, null);
        assertEquals(200, listed.statusCode());
        var item = Json.tree(listed.body()).get(0);
        assertTrue(item.path("rules").path("success").isNull());
        assertTrue(item.path("rules").path("alreadyDone").path("value").asBoolean());
        assertTrue(item.path("curl").isMissingNode());
        assertTrue(item.path("rawCurl").isMissingNode());
        assertEquals(400, post(path, Map.of("name", " ")).statusCode());
        assertEquals(
                400,
                post(
                                path,
                                Map.of(
                                        "name",
                                        "错误规则",
                                        "rules",
                                        new ResultRules(
                                                new ResultRules.Match("bad[*]", 0, null),
                                                null,
                                                null,
                                                null)))
                        .statusCode());
        String target = path + "/" + id.asString();
        assertEquals(
                200,
                call("PUT", target, Map.of("name", "更新奖励", "rules", rules, "version", 1))
                        .statusCode());
        assertEquals(
                409,
                call("PUT", target, Map.of("name", "冲突", "rules", rules, "version", 1))
                        .statusCode());
        assertEquals(
                404,
                call("DELETE", "/platforms/" + other + "/templates/" + id.asString(), Map.of())
                        .statusCode());
        assertEquals(200, call("DELETE", target, Map.of()).statusCode());
        assertTrue(templates.queryList(platform).isEmpty());
    }

    @Test
    void previewNeedsOnlyCurlAndDoesNotSendTheRequest() throws Exception {
        var response =
                post(
                        "/requests/preview",
                        Map.of(
                                "curl",
                                "curl 'https://example.invalid/check?x=%2f&x=2' -H 'Cookie:"
                                        + " test-only=one' --data-raw '{\"code\":0}'"));
        assertEquals(200, response.statusCode());
        var spec = Json.tree(response.body()).path("spec");
        assertEquals("POST", spec.path("method").asString());
        assertEquals("https://example.invalid/check?x=%2f&x=2", spec.path("rawUrl").asString());
        assertEquals("test-only=one", spec.path("headers").get(0).path("value").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void backupOptionalFlagsDefaultToFalseButReplacementStillRequiresConfirmation()
            throws Exception {
        addPlatform("备份接口测试", "", true);
        var exported = post("/backups/export", Map.of("includeRequests", false));
        assertEquals(200, exported.statusCode());
        var backup = Json.map(exported.body());
        var preview = post("/backups/preview", Map.of("backup", backup));
        assertEquals(200, preview.statusCode());
        assertFalse(Json.tree(preview.body()).path("includesRequests").asBoolean());
        var unconfirmed = post("/backups/import", Map.of("backup", backup));
        assertEquals(400, unconfirmed.statusCode());
        var restored = post("/backups/import", Map.of("backup", backup, "replace", true));
        assertEquals(200, restored.statusCode());
        assertTrue(settings.query().paused());
    }

    @Test
    void ordinaryCrudPathsAndJsonShapesStayStableAcrossTheWholeCatalog() throws Exception {
        var created =
                post(
                        "/platforms",
                        Map.of("name", "完整接口契约", "note", "fixture-note", "enabled", true));
        String platform = Json.tree(created.body()).path("id").asString();
        try {
            var account =
                    post(
                            "/platforms/" + platform + "/accounts",
                            Map.of("alias", "账号", "enabled", true));
            assertEquals(200, account.statusCode(), account.body());
            String accountId = Json.tree(account.body()).path("id").asString();
            String curl =
                    "curl 'https://example.invalid/check?x=%2f&x=2' -H 'Cookie: fixture-api-secret'"
                        + " --data-raw '原始请求体'";
            var added =
                    post(
                            "/accounts/" + accountId + "/requests",
                            Map.of(
                                    "name",
                                    "请求",
                                    "curl",
                                    curl,
                                    "enabled",
                                    false,
                                    "rules",
                                    ResultRules.defaults()));
            assertEquals(200, added.statusCode(), added.body());
            String request = Json.tree(added.body()).path("id").asString();
            var tree = call("GET", "/platforms", null);
            assertEquals(200, tree.statusCode());
            assertFalse(tree.body().contains("fixture-api-secret"));
            assertFalse(tree.body().contains("rawCurl"));
            var revision = call("GET", "/requests/" + request + "/revision", null);
            assertEquals(200, revision.statusCode());
            assertEquals(java.util.Set.of("rawCurl", "spec"), Json.map(revision.body()).keySet());
            assertEquals(curl, Json.tree(revision.body()).path("rawCurl").asString());
            assertEquals("no-store", revision.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(
                    404,
                    call("GET", "/requests/" + request + "/revision?revision=99", null)
                            .statusCode());
            var changed =
                    post("/requests/" + request + "/revisions", Map.of("curl", curl, "version", 1));
            assertEquals(200, changed.statusCode());
            assertEquals(2, Json.tree(changed.body()).path("revision").asInt());
            assertEquals(
                    409,
                    post("/requests/" + request + "/revisions", Map.of("curl", curl, "version", 1))
                            .statusCode());
            assertEquals(
                    200,
                    call(
                                    "PUT",
                                    "/requests/" + request,
                                    Map.of(
                                            "name",
                                            "改名",
                                            "enabled",
                                            false,
                                            "rules",
                                            ResultRules.defaults(),
                                            "version",
                                            2))
                            .statusCode());
            assertEquals(
                    200,
                    call(
                                    "PUT",
                                    "/accounts/" + accountId,
                                    Map.of("alias", "改名账号", "enabled", true, "version", 1))
                            .statusCode());
            var plan = call("GET", "/platforms/" + platform + "/schedule", null);
            assertEquals(200, plan.statusCode());
            var planInput = Json.map(plan.body());
            assertEquals(
                    java.util.Set.of(
                            "enabled",
                            "frequency",
                            "weekdays",
                            "times",
                            "timezone",
                            "intervalSeconds",
                            "catchupMinutes",
                            "skipCompletedDaily",
                            "revision"),
                    planInput.keySet());
            assertEquals(
                    200,
                    call("PUT", "/platforms/" + platform + "/schedule", planInput).statusCode());
            assertEquals(
                    409,
                    call("PUT", "/platforms/" + platform + "/schedule", planInput).statusCode());
            assertEquals(200, call("GET", "/schedules", null).statusCode());
            assertEquals(200, call("GET", "/dashboard", null).statusCode());
            assertEquals(200, call("GET", "/system/status", null).statusCode());
            assertEquals(
                    409,
                    post(
                                    "/runs",
                                    Map.of(
                                            "scope",
                                            "request",
                                            "id",
                                            request,
                                            "force",
                                            true,
                                            "key",
                                            "disabled-request-fixture"))
                            .statusCode());
            assertEquals(404, call("GET", "/runs/1", null).statusCode());
            assertEquals(404, post("/batches/1/cancel", Map.of()).statusCode());
            assertEquals(200, call("DELETE", "/requests/" + request, null).statusCode());
            assertEquals(404, call("DELETE", "/requests/" + request, null).statusCode());
            assertEquals(200, call("DELETE", "/accounts/" + accountId, null).statusCode());
        } finally {
            call("DELETE", "/platforms/" + platform, null);
        }
    }

    @Test
    void newBackupContractIsStrictPlainTypedAndHasNoPasswordOrOldFormat() throws Exception {
        String p = addPlatform("明文备份接口", "", true), a = addAccount(p, "备份账号", true);
        String curl = "curl 'https://example.invalid/plain' -H 'Cookie: plain-api-credential'";
        addRequest(a, "明文请求", curl, ResultRules.defaults(), true);
        var exported = post("/backups/export", Map.of("includeRequests", true));
        assertEquals(200, exported.statusCode(), exported.body());
        assertEquals(java.util.Set.of("format", "payload"), Json.map(exported.body()).keySet());
        var tree = Json.tree(exported.body());
        assertEquals("signdesk-plain-v1", tree.path("format").asString());
        assertTrue(exported.body().contains("plain-api-credential"));
        assertEquals(
                java.util.Set.of(
                        "includesRequests",
                        "settings",
                        "platforms",
                        "accounts",
                        "requests",
                        "templates",
                        "schedules",
                        "completed",
                        "pending"),
                Json.map(tree.path("payload").toString()).keySet());
        var preview = post("/backups/preview", Map.of("backup", Json.map(exported.body())));
        assertEquals(200, preview.statusCode());
        assertEquals(
                java.util.Set.of(
                        "platforms",
                        "accounts",
                        "requests",
                        "templates",
                        "includesRequests",
                        "mode",
                        "message"),
                Json.map(preview.body()).keySet());
        assertEquals("replace", Json.tree(preview.body()).path("mode").asString());
        var config = post("/backups/export", Map.of());
        assertEquals(200, config.statusCode());
        assertFalse(config.body().contains("rawCurl"));
        assertEquals(
                400,
                post("/backups/export", Map.of("password", "obsolete-not-supported")).statusCode());
        for (String old : java.util.List.of("signdesk-config", "signdesk-backup-v1")) {
            var bad = Json.map(exported.body());
            bad.put("format", old);
            var rejected = post("/backups/preview", Map.of("backup", bad));
            assertEquals(400, rejected.statusCode());
            assertFalse(rejected.body().contains("plain-api-credential"));
        }
        for (Object id :
                java.util.List.of(123, "0", "01", "9223372036854775808", "fixture-old-uuid")) {
            var bad = Json.map(exported.body());
            var payload = (Map<String, Object>) bad.get("payload");
            ((java.util.List<Map<String, Object>>) payload.get("platforms"))
                    .getFirst()
                    .put("id", id);
            assertEquals(400, post("/backups/preview", Map.of("backup", bad)).statusCode());
        }
        var missing = Json.map(exported.body());
        ((Map<String, Object>) missing.get("payload")).remove("templates");
        assertEquals(400, post("/backups/preview", Map.of("backup", missing)).statusCode());
        assertEquals(
                400,
                post(
                                "/backups/import",
                                Map.of("backup", Json.map(exported.body()), "replace", false))
                        .statusCode());
    }

    @Test
    void chunkedImportBodyIsLimitedByActualBytesAtTheExactTwelveMiBBoundary() throws Exception {
        var config = post("/backups/export", Map.of("includeRequests", false));
        assertEquals(200, config.statusCode());
        byte[] json =
                Json.write(Map.of("backup", Json.map(config.body())))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] exact = new byte[12582912];
        java.util.Arrays.fill(exact, (byte) ' ');
        System.arraycopy(json, 0, exact, 0, json.length);
        var allowed = chunkedPreview(exact);
        assertEquals(200, allowed.statusCode(), allowed.body());
        byte[] excessive = java.util.Arrays.copyOf(exact, exact.length + 1);
        excessive[excessive.length - 1] = ' ';
        var rejected = chunkedPreview(excessive);
        assertEquals(400, rejected.statusCode());
        assertTrue(rejected.body().contains("12 MiB"));
    }

    HttpResponse<String> chunkedPreview(byte[] bytes) throws Exception {
        var publisher =
                HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(bytes));
        assertEquals(-1, publisher.contentLength());
        return client.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + environment.getProperty("local.server.port")
                                                + "/api/backups/preview"))
                        .header("Content-Type", "application/json")
                        .POST(publisher)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
