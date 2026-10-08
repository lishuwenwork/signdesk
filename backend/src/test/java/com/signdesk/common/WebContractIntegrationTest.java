package com.signdesk.common;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.engine.ResultRules;
import com.signdesk.platform.CatalogService;
import com.signdesk.run.SettingsService;

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
        registry.add("signdesk.key-file", () -> DIR.resolve("master.key").toString());
    }

    @Autowired Environment environment;
    @Autowired CatalogService catalog;
    @Autowired SettingsService settings;
    final HttpClient client = HttpClient.newHttpClient();

    @Test
    void settingsApiPersistsProxyAndValidatesBeforeWriting() throws Exception {
        var current = settings.get();
        var uri = URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + "/api/settings");
        var payload = Json.map(Json.write(current));
        payload.put("proxy", Map.of("mode", "http", "host", "127.0.0.1", "port", 12345));
        var response = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(payload))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var fetched = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(12345, Json.tree(fetched.body()).path("proxy").path("port").asInt());
        payload.put("version", settings.get().version());
        payload.put("proxy", Map.of("mode", "http", "host", "127.0.0.1", "port", 65536));
        var invalid = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(payload))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalid.statusCode());
        assertEquals(12345, settings.get().proxy().port());
        var saved = settings.get();
        settings.save(new SettingsService.Settings(current.paused(), current.concurrency(),
                current.timeoutSeconds(), current.retentionDays(), saved.version(), current.proxy()));
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
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void templateApiValidatesScopeAndVersionsWithoutExposingRequestContent() throws Exception {
        String platform = catalog.addPlatform("接口模板测试", "", true);
        String other = catalog.addPlatform("隔离平台", "", true);
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
        assertEquals(400, post(path, Map.of("name", "错误规则", "rules",
                new ResultRules(new ResultRules.Match("bad[*]", 0, null), null, null, null))).statusCode());
        String target = path + "/" + id.asString();
        assertEquals(200, call("PUT", target, Map.of("name", "更新奖励", "rules", rules, "version", 1)).statusCode());
        assertEquals(409, call("PUT", target, Map.of("name", "冲突", "rules", rules, "version", 1)).statusCode());
        assertEquals(404, call("DELETE", "/platforms/" + other + "/templates/" + id.asString(), Map.of()).statusCode());
        assertEquals(200, call("DELETE", target, Map.of()).statusCode());
        assertTrue(catalog.templates(platform).isEmpty());
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
        catalog.addPlatform("备份接口测试", "", true);
        var exported = post("/backups/export", Map.of("includeRequests", false, "password", ""));
        assertEquals(200, exported.statusCode());
        var backup = Json.map(exported.body());
        var preview = post("/backups/preview", Map.of("backup", backup, "password", ""));
        assertEquals(200, preview.statusCode());
        assertFalse(Json.tree(preview.body()).path("includesRequests").asBoolean());
        var unconfirmed = post("/backups/import", Map.of("backup", backup, "password", ""));
        assertEquals(400, unconfirmed.statusCode());
        var restored =
                post("/backups/import", Map.of("backup", backup, "password", "", "replace", true));
        assertEquals(200, restored.statusCode());
        assertTrue(settings.get().paused());
    }
}
