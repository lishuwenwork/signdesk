package com.signdesk.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {
    static final Path DIR;
    static final Map<String, String> CREDENTIALS = Map.of("username", "auth-fixture-admin", "password", "auth-fixture-password");
    static {
        try { DIR = Files.createTempDirectory("signdesk-auth-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("signdesk.data-dir", () -> DIR.resolve("data").toString());
        registry.add("signdesk.auth.username", () -> CREDENTIALS.get("username"));
        registry.add("signdesk.auth.password", () -> CREDENTIALS.get("password"));
    }
    @Autowired Environment environment;
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    final HttpClient anonymous = HttpClient.newHttpClient();

    String origin() { return "http://127.0.0.1:" + environment.getProperty("local.server.port"); }
    HttpResponse<String> call(HttpClient caller, String method, String path, String body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin() + path));
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return caller.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(HttpClient caller, String path) throws Exception {
        return call(caller, "GET", path, null, Map.of());
    }
    HttpResponse<String> post(String path, Object body) throws Exception {
        return call(client, "POST", path, Json.write(body), Map.of("Content-Type", "application/json", "Origin", origin()));
    }
    HttpResponse<String> login() throws Exception {
        var response = post("/api/auth/login", CREDENTIALS);
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }
    String sessionCookie() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID"))
                .map(c -> c.getName() + "=" + c.getValue()).findFirst().orElseThrow();
    }

    @Test
    void anonymousManagementReadsWritesAndSensitiveDataAreProtected() throws Exception {
        for (String path : java.util.List.of("/api/platforms", "/api/settings", "/api/dashboard", "/api/system/status",
                "/api/runs", "/api/runs/1", "/api/requests/1/revision", "/api/schedules")) {
            var response = get(anonymous, path);
            assertEquals(401, response.statusCode(), path);
            assertEquals(java.util.Set.of("message"), Json.map(response.body()).keySet());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        }
        for (String path : java.util.List.of("/api/platforms", "/api/runs", "/api/backups/export", "/api/backups/preview", "/api/backups/import")) {
            var response = call(anonymous, "POST", path, "{}", Map.of("Content-Type", "application/json"));
            assertEquals(401, response.statusCode(), path);
        }
        assertEquals(401, call(anonymous, "DELETE", "/api/platforms/1", "{}", Map.of("Content-Type", "application/json")).statusCode());
    }

    @Test
    void sessionQueryDoesNotCreateAnAnonymousSessionAndHealthRevealsOnlyReadiness() throws Exception {
        var state = get(anonymous, "/api/auth/session");
        assertEquals(200, state.statusCode());
        assertFalse(Json.tree(state.body()).path("authenticated").asBoolean());
        assertTrue(Json.tree(state.body()).path("username").isNull());
        assertTrue(state.headers().allValues("Set-Cookie").isEmpty());
        var health = get(anonymous, "/api/health");
        assertEquals(200, health.statusCode());
        assertEquals(Map.of("ready", true), Json.map(health.body()));
        assertEquals("no-store", health.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void configuredLoginRotatesTheSessionAndLogoutInvalidatesOldCookies() throws Exception {
        var loggedIn = login();
        assertEquals(Map.of("authenticated", true, "username", CREDENTIALS.get("username")), Json.map(loggedIn.body()));
        String header = loggedIn.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("SameSite=Lax"), header);
        assertFalse(header.contains("Max-Age="), header);
        assertFalse(loggedIn.body().contains(CREDENTIALS.get("password")));
        assertEquals(200, get(client, "/api/platforms").statusCode());
        assertTrue(Json.tree(get(client, "/api/auth/session").body()).path("authenticated").asBoolean());
        String oldCookie = sessionCookie();
        login();
        String newCookie = sessionCookie();
        assertNotEquals(oldCookie, newCookie);
        assertEquals(401, call(anonymous, "GET", "/api/settings", null, Map.of("Cookie", oldCookie)).statusCode());
        var logout = post("/api/auth/logout", Map.of());
        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertEquals(401, call(anonymous, "GET", "/api/settings", null, Map.of("Cookie", newCookie)).statusCode());
        assertFalse(Json.tree(get(client, "/api/auth/session").body()).path("authenticated").asBoolean());
        assertEquals(200, post("/api/auth/logout", Map.of()).statusCode());
    }

    @Test
    void wrongCredentialsReturnTheSameErrorWithoutDefaultAccountOrPayloadLeaks() throws Exception {
        for (var input : java.util.List.of(Map.of("username", "admin", "password", "123456"),
                Map.of("username", CREDENTIALS.get("username"), "password", "private-incorrect-password"))) {
            var response = post("/api/auth/login", input);
            assertEquals(401, response.statusCode());
            assertEquals(Map.of("message", "用户名或密码错误"), Json.map(response.body()));
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        }
        assertEquals(401, get(client, "/api/platforms").statusCode());
        login(); // A successful login also leaves other tests independent of the failure window.
    }

    @Test
    void inputValidationAndActualLoginBodySizeLimitsDoNotCreateSessions() throws Exception {
        for (var input : java.util.List.of(Map.of("username", "", "password", "fixture"),
                Map.of("username", "x".repeat(65), "password", "fixture"),
                Map.of("username", "fixture", "password", "x".repeat(257)))) {
            assertEquals(400, post("/api/auth/login", input).statusCode());
        }
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[8193]));
        var response = client.send(HttpRequest.newBuilder(URI.create(origin() + "/api/auth/login"))
                .header("Content-Type", "application/json").POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertTrue(cookies.getCookieStore().getCookies().isEmpty());
    }

    @Test
    void loginAndLogoutRetainTheSameOriginAndJsonWriteBoundary() throws Exception {
        String payload = Json.write(CREDENTIALS);
        assertEquals(403, call(client, "POST", "/api/auth/login", payload,
                Map.of("Content-Type", "application/json", "Origin", "https://example.invalid")).statusCode());
        assertEquals(403, call(client, "POST", "/api/auth/login", payload,
                Map.of("Content-Type", "application/json", "Sec-Fetch-Site", "cross-site")).statusCode());
        assertEquals(403, call(client, "POST", "/api/auth/login", payload,
                Map.of("Content-Type", "application/json", "Origin", origin().replace("http:", "https:"))).statusCode());
        assertEquals(415, call(client, "POST", "/api/auth/login", "username=fixture",
                Map.of("Content-Type", "application/x-www-form-urlencoded")).statusCode());
        login();
        assertEquals(403, call(client, "POST", "/api/auth/logout", "{}",
                Map.of("Content-Type", "application/json", "Origin", "https://example.invalid")).statusCode());
        assertEquals(200, get(client, "/api/settings").statusCode());
    }

    @Test
    void publicWhitelistIsExactAndPathParametersCannotBypassAuthentication() throws Exception {
        for (String path : java.util.List.of("/api;/settings", "/api/health;/../settings", "/api/%73ettings",
                "/api/auth/session/../settings", "/api/auth/login/extra")) {
            var response = get(anonymous, path);
            assertTrue(response.statusCode() == 400 || response.statusCode() == 401, path + ": " + response.statusCode());
            assertFalse(response.body().contains(CREDENTIALS.get("password")));
        }
        assertEquals(401, get(anonymous, "/api/auth/login").statusCode());
        assertEquals(401, call(anonymous, "POST", "/api/health", "{}", Map.of("Content-Type", "application/json")).statusCode());
        assertEquals(401, call(anonymous, "GET", "/api/settings", null, Map.of("Cookie", "JSESSIONID=expired-fixture-session")).statusCode());
    }
}
