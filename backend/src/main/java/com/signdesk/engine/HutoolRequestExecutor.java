package com.signdesk.engine;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.http.HttpConfig;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

@Component
public class HutoolRequestExecutor implements AutoCloseable {
    public record Result(String status, Integer httpStatus, long durationMs, String summary) {}

    private final NetworkPolicy policy;
    private final ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "http-deadline");
                        t.setDaemon(true);
                        return t;
                    });

    public HutoolRequestExecutor(NetworkPolicy policy) {
        this.policy = policy;
    }

    public Result execute(RequestSpec spec, ResultRules rules, int defaultTimeout) {
        long start = System.nanoTime();
        int timeout =
                spec.timeoutMillis() == 0
                        ? defaultTimeout * 1000
                        : Math.min(spec.timeoutMillis(), defaultTimeout * 1000);
        AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        ScheduledFuture<?> watchdog = null;
        try {
            RequestSpec current = spec;
            for (int hop = 0; hop <= 5; hop++) {
                policy.validate(current.rawUrl());
                int remaining =
                        timeout - (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (remaining <= 0) return result("unknown", null, start, "达到执行期限，结果待确认");
                final String rawUrl = current.rawUrl();
                // The handler creates the connection with the original URL, bypassing Hutool
                // normalization.
                URLStreamHandler handler =
                        new URLStreamHandler() {
                            @Override
                            protected URLConnection openConnection(URL url)
                                    throws java.io.IOException {
                                return openConnection(url, Proxy.NO_PROXY);
                            }

                            @Override
                            protected URLConnection openConnection(URL url, Proxy proxy)
                                    throws java.io.IOException {
                                HttpURLConnection c =
                                        (HttpURLConnection) new URL(rawUrl).openConnection(proxy);
                                connection.set(c);
                                return c;
                            }
                        };
                HttpConfig config =
                        HttpConfig.create()
                                .setConnectionTimeout(remaining)
                                .setReadTimeout(remaining)
                                .setMaxRedirectCount(0)
                                .setUseDefaultContentTypeIfNull(false)
                                .setHostnameVerifier(
                                        HttpsURLConnection.getDefaultHostnameVerifier())
                                .setSSLSocketFactory(SSLContext.getDefault().getSocketFactory());
                HttpRequest request =
                        new HttpRequest(UrlBuilder.ofHttpWithoutEncode(rawUrl))
                                .setUrlHandler(handler)
                                .setConfig(config)
                                .method(Method.valueOf(current.method()))
                                .disableCookie();
                request.clearHeaders();
                String cookie =
                        current.headers().stream()
                                .filter(h -> h.name().equalsIgnoreCase("Cookie"))
                                .map(RequestSpec.Header::value)
                                .collect(Collectors.joining("; "));
                if (!cookie.isEmpty()) request.cookie(cookie);
                for (var h : current.headers())
                    if (!h.name().equalsIgnoreCase("Cookie"))
                        request.header(h.name(), h.value(), false);
                byte[] body = current.bodyBytes();
                if (body.length > 0)
                    request.body(body).setRest(true).setFixedContentLength(body.length);
                watchdog =
                        deadlines.schedule(
                                () -> {
                                    var c = connection.get();
                                    if (c != null) c.disconnect();
                                },
                                remaining,
                                TimeUnit.MILLISECONDS);
                try (HttpResponse response = request.executeAsync()) {
                    int status = response.getStatus();
                    if (java.util.Set.of(301, 302, 303, 307, 308).contains(status)
                            && current.followRedirects()) {
                        String location = response.header("Location");
                        if (location == null || hop == 5)
                            return result("unknown", status, start, "重定向缺少地址或超过 5 跳，结果待确认");
                        var origin = CurlParser.validateUrl(current.rawUrl());
                        var next = CurlParser.validateUrl(origin.resolve(location).toString());
                        int oldPort =
                                origin.getPort() < 0
                                        ? (origin.getScheme().equals("https") ? 443 : 80)
                                        : origin.getPort();
                        int nextPort =
                                next.getPort() < 0
                                        ? (next.getScheme().equals("https") ? 443 : 80)
                                        : next.getPort();
                        if (!origin.getScheme().equals(next.getScheme())
                                || !origin.getHost().equalsIgnoreCase(next.getHost())
                                || oldPort != nextPort)
                            return result("unknown", status, start, "跨来源重定向已停止，避免将完整请求中的凭证转发到其他来源");
                        boolean get =
                                status == 303 && !current.method().equals("HEAD")
                                        || (status == 301 || status == 302)
                                                && current.method().equals("POST");
                        var redirectHeaders =
                                get
                                        ? current.headers().stream()
                                                .filter(
                                                        h ->
                                                                !h.name()
                                                                                .equalsIgnoreCase(
                                                                                        "Content-Type")
                                                                        && !h.name()
                                                                                .equalsIgnoreCase(
                                                                                        "Content-Length"))
                                                .toList()
                                        : current.headers();
                        current =
                                new RequestSpec(
                                        get ? "GET" : current.method(),
                                        next.toString(),
                                        redirectHeaders,
                                        get ? new byte[0] : current.bodyBytes(),
                                        true,
                                        current.timeoutMillis());
                        continue;
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    var input = response.bodyStream();
                    if (input != null) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (bytes.size() + read > 1048576)
                                return result("unknown", status, start, "响应超过 1 MiB，结果待确认");
                            bytes.write(buffer, 0, read);
                            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > timeout)
                                return result("unknown", status, start, "达到执行期限，结果待确认");
                        }
                    }
                    String classification =
                            rules.classify(status, bytes.toString(StandardCharsets.UTF_8));
                    String summary =
                            switch (classification) {
                                case "success" -> "命中成功规则";
                                case "already_done" -> "命中已完成规则";
                                case "expired" -> "凭证过期，请更新 cURL";
                                case "failed" -> "HTTP 或业务规则判定失败";
                                default -> "响应未命中可确认规则，请核对平台实际状态";
                            };
                    return result(classification, status, start, summary);
                } finally {
                    if (watchdog != null) watchdog.cancel(false);
                }
            }
            return result("unknown", null, start, "重定向未完成，结果待确认");
        } catch (com.signdesk.common.ApiException e) {
            return result("failed", null, start, e.getMessage());
        } catch (Exception e) {
            return result("unknown", null, start, "连接异常或超时，可能已发送；请核对平台后再执行");
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            var c = connection.get();
            if (c != null) c.disconnect();
        }
    }

    private Result result(String status, Integer http, long start, String text) {
        return new Result(
                status, http, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), text);
    }

    @Override
    public void close() {
        deadlines.shutdownNow();
    }
}
