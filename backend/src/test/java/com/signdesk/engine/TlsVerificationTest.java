package com.signdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

class TlsVerificationTest {
    @Test
    void verifiesBothTrustChainAndHostname() throws Exception {
        Path directory = Files.createTempDirectory("signdesk-tls-");
        Path file = directory.resolve("fixture.p12");
        String tool =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").toLowerCase().contains("win")
                                        ? "keytool.exe"
                                        : "keytool")
                        .toString();
        Process process =
                new ProcessBuilder(
                                tool,
                                "-genkeypair",
                                "-alias",
                                "fixture",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-validity",
                                "1",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "SAN=dns:localhost",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                file.toString(),
                                "-storepass",
                                "test-fixture-password",
                                "-noprompt")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) {
            store.load(input, "test-fixture-password".toCharArray());
        }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, "test-fixture-password".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        AtomicInteger hits = new AtomicInteger();
        server.createContext(
                "/",
                e -> {
                    hits.incrementAndGet();
                    byte[] bytes = "{\"code\":0}".getBytes();
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        SSLContext previous = SSLContext.getDefault();
        int port = server.getAddress().getPort();
        try (var http = new HutoolRequestExecutor(new NetworkPolicy("127.0.0.1,localhost"))) {
            var parser = new CurlParser();
            assertEquals(
                    "unknown",
                    http.execute(
                                    parser.parse(
                                                    "curl 'https://localhost:"
                                                            + port
                                                            + "/' --max-time 2")
                                            .spec(),
                                    ResultRules.defaults(),
                                    20)
                            .status());
            assertEquals(0, hits.get(), "untrusted certificate must not reach handler");
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext trusted = SSLContext.getInstance("TLS");
            trusted.init(null, trust.getTrustManagers(), null);
            SSLContext.setDefault(trusted);
            assertEquals(
                    "unknown",
                    http.execute(
                                    parser.parse(
                                                    "curl 'https://127.0.0.1:"
                                                            + port
                                                            + "/' --max-time 2")
                                            .spec(),
                                    ResultRules.defaults(),
                                    20)
                            .status());
            assertEquals(
                    0, hits.get(), "trusted certificate with wrong host must not reach handler");
            assertEquals(
                    "success",
                    http.execute(
                                    parser.parse(
                                                    "curl 'https://localhost:"
                                                            + port
                                                            + "/' --max-time 2")
                                            .spec(),
                                    ResultRules.defaults(),
                                    20)
                            .status());
            assertEquals(1, hits.get());
            try (var proxy = new TunnelProxyFixture(port, false)) {
                var routing = new ProxySettings("http", "127.0.0.1", proxy.port());
                var spec = parser.parse("curl 'https://localhost:" + port + "/?sig=%2f%2F' --max-time 2").spec();
                assertEquals("success", http.execute(spec, ResultRules.defaults(), 20, routing).status());
                assertEquals(2, hits.get());
                assertTrue(proxy.target.get().startsWith("CONNECT localhost:"));
                SSLContext.setDefault(previous);
                var rejected = http.execute(spec, ResultRules.defaults(), 20, routing);
                assertEquals("unknown", rejected.status());
                assertTrue(rejected.summary().contains("TLS"), rejected.summary());
                assertEquals(2, hits.get(), "proxy must not bypass certificate verification");
            }
        } finally {
            SSLContext.setDefault(previous);
            server.stop(0);
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
