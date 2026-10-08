package com.signdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

class HutoolExecutorTest {
    HttpServer server;
    HutoolRequestExecutor executor;
    String url;
    AtomicReference<String> query = new AtomicReference<>(),
            body = new AtomicReference<>(),
            cookie = new AtomicReference<>();
    AtomicReference<List<String>> headers = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            return t;
                        }));
        server.createContext(
                "/echo",
                e -> {
                    query.set(e.getRequestURI().toASCIIString());
                    body.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    cookie.set(e.getRequestHeaders().getFirst("Cookie"));
                    headers.set(e.getRequestHeaders().get("X-Duplicate"));
                    e.getResponseHeaders().add("Set-Cookie", "server-cookie=must-not-leak");
                    byte[] result = "{\"code\":0}".getBytes();
                    e.sendResponseHeaders(200, result.length);
                    e.getResponseBody().write(result);
                    e.close();
                });
        server.createContext(
                "/slow",
                e -> {
                    try {
                        Thread.sleep(1500);
                        byte[] result = "{\"code\":0}".getBytes();
                        e.sendResponseHeaders(200, result.length);
                        e.getResponseBody().write(result);
                    } catch (Exception ignored) {
                    } finally {
                        e.close();
                    }
                });
        server.createContext(
                "/large",
                e -> {
                    byte[] result = new byte[1048577];
                    e.sendResponseHeaders(200, result.length);
                    try {
                        e.getResponseBody().write(result);
                    } catch (Exception ignored) {
                    }
                    e.close();
                });
        server.createContext(
                "/redirect",
                e -> {
                    e.getResponseHeaders().add("Location", "/echo");
                    e.sendResponseHeaders(302, -1);
                    e.close();
                });
        server.createContext(
                "/external",
                e -> {
                    e.getResponseHeaders().add("Location", "http://other.example/collect");
                    e.sendResponseHeaders(302, -1);
                    e.close();
                });
        server.createContext(
                "/preserve",
                e -> {
                    e.getResponseHeaders().add("Location", "/echo?sig=%2f%2F");
                    e.sendResponseHeaders(307, -1);
                    e.close();
                });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = new HutoolRequestExecutor(new NetworkPolicy("127.0.0.1"));
    }

    @AfterEach
    void stop() {
        executor.close();
        server.stop(0);
    }

    @Test
    void preservesRawBytesAndDoesNotShareCookies() {
        var parser = new CurlParser();
        String raw = "/echo?a=%2f%2F&x=1&x=2&sig=a+b%20c";
        var s =
                parser.parse(
                                "curl '"
                                        + url
                                        + raw
                                        + "' -H 'Cookie: account=one' -H 'X-Duplicate: first' -H"
                                        + " 'X-Duplicate: second' --data-raw '{\"name\": \"中文\","
                                        + " \"n\": 1}'")
                        .spec();
        var result = executor.execute(s, ResultRules.defaults(), 20);
        assertEquals("success", result.status(), result.summary());
        assertEquals(raw, query.get());
        assertEquals("{\"name\": \"中文\", \"n\": 1}", body.get());
        assertEquals("account=one", cookie.get());
        assertEquals(2, headers.get().size());
        assertTrue(headers.get().containsAll(List.of("first", "second")));
        executor.execute(
                parser.parse("curl '" + url + "/echo' -b 'account=two'").spec(),
                ResultRules.defaults(),
                20);
        assertEquals("account=two", cookie.get());
        executor.execute(
                parser.parse("curl '" + url + "/echo'").spec(), ResultRules.defaults(), 20);
        assertTrue(cookie.get() == null || cookie.get().isEmpty());
    }

    @Test
    void timeoutIsUnknownAndBounded() {
        var result =
                executor.execute(
                        new CurlParser().parse("curl '" + url + "/slow' --max-time 1").spec(),
                        ResultRules.defaults(),
                        20);
        assertEquals("unknown", result.status());
        assertTrue(result.durationMs() < 1400, "deadline exceeded");
    }

    @Test
    void largeResponsesAndRedirectsAreNotFalseSuccess() {
        assertEquals(
                "unknown",
                executor.execute(
                                new CurlParser().parse("curl '" + url + "/large'").spec(),
                                ResultRules.defaults(),
                                20)
                        .status());
        assertEquals(
                "unknown",
                executor.execute(
                                new CurlParser().parse("curl -L '" + url + "/external'").spec(),
                                ResultRules.defaults(),
                                20)
                        .status());
        assertNull(query.get(), "redirect must not forward credentials");
    }

    @Test
    void sameOriginRedirectsPreserve307AndConvertPost302ToGet() {
        var parser = new CurlParser();
        assertEquals(
                "success",
                executor.execute(
                                parser.parse(
                                                "curl -L '"
                                                        + url
                                                        + "/preserve' --data-raw 'exact-body' -b"
                                                        + " 'account=one'")
                                        .spec(),
                                ResultRules.defaults(),
                                20)
                        .status());
        assertEquals("exact-body", body.get());
        assertEquals("/echo?sig=%2f%2F", query.get());
        assertEquals("account=one", cookie.get());
        assertEquals(
                "success",
                executor.execute(
                                parser.parse(
                                                "curl -L '"
                                                        + url
                                                        + "/redirect' --data-raw 'removed-on-302'")
                                        .spec(),
                                ResultRules.defaults(),
                                20)
                        .status());
        assertEquals("", body.get());
    }

    @Test
    void localNetworkBlockedByDefault() {
        try (var other = new HutoolRequestExecutor(new NetworkPolicy(""))) {
            assertEquals(
                    "failed",
                    other.execute(
                                    new CurlParser().parse("curl '" + url + "/echo'").spec(),
                                    ResultRules.defaults(),
                                    20)
                            .status());
            assertNull(query.get());
        }
    }
}
