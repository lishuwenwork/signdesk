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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

@Component
public class HutoolRequestExecutor implements AutoCloseable {
    public static final int MAX_RESPONSE_BYTES = 1048576;

    public record Result(
            String status, Integer httpStatus, long durationMs, String summary, ResponseBody response) {
        public Result(String status, Integer httpStatus, long durationMs, String summary) {
            this(status, httpStatus, durationMs, summary, null);
        }
    }

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
        return execute(spec, rules, defaultTimeout, ProxySettings.system());
    }

    public Result execute(
            RequestSpec spec, ResultRules rules, int defaultTimeout, ProxySettings proxySettings) {
        long start = System.nanoTime();
        int timeout =
                spec.timeoutMillis() == 0
                        ? defaultTimeout * 1000
                        : Math.min(spec.timeoutMillis(), defaultTimeout * 1000);
        AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        AtomicBoolean expired = new AtomicBoolean();
        Integer httpStatus = null;
        ByteArrayOutputStream received = null;
        String contentType = null, charset = StandardCharsets.UTF_8.name();
        String phase = "校验目标地址";
        ScheduledFuture<?> watchdog = null;
        try {
            Proxy proxy = proxySettings.toProxy();
            RequestSpec current = spec;
            for (int hop = 0; hop <= 5; hop++) {
                policy.validate(current.rawUrl());
                int remaining =
                        timeout - (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (remaining <= 0)
                    return result("unknown", httpStatus, start, timeoutSummary(phase, timeout));
                final String rawUrl = current.rawUrl();
                // The handler creates the connection with the original URL, bypassing Hutool
                // normalization.
                URLStreamHandler handler =
                        new URLStreamHandler() {
                            @Override
                            protected URLConnection openConnection(URL url)
                                    throws java.io.IOException {
                                HttpURLConnection c =
                                        (HttpURLConnection) new URL(rawUrl).openConnection();
                                connection.set(c);
                                return c;
                            }

                            @Override
                            protected URLConnection openConnection(URL url, Proxy selectedProxy)
                                    throws java.io.IOException {
                                HttpURLConnection c =
                                        (HttpURLConnection) new URL(rawUrl).openConnection(selectedProxy);
                                connection.set(c);
                                return c;
                            }
                        };
                HttpConfig config =
                        HttpConfig.create()
                                .setConnectionTimeout(remaining)
                                .setReadTimeout(remaining)
                                .setProxy(proxy)
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
                                    expired.set(true);
                                    var c = connection.get();
                                    if (c != null) c.disconnect();
                                },
                                remaining,
                                TimeUnit.MILLISECONDS);
                phase = "连接/发送请求";
                try (HttpResponse response = request.executeAsync()) {
                    int status = response.getStatus();
                    httpStatus = status;
                    phase = "读取响应";
                    String redirectStop = null;
                    if (java.util.Set.of(301, 302, 303, 307, 308).contains(status)
                            && current.followRedirects()) {
                        String location = response.header("Location");
                        if (location == null || hop == 5) {
                            redirectStop = "重定向缺少地址或超过 5 跳，结果待确认";
                        } else {
                            var origin = CurlParser.validateUrl(current.rawUrl());
                            var next = CurlParser.validateUrl(origin.resolve(location).toString());
                            int oldPort = origin.getPort() < 0
                                    ? (origin.getScheme().equals("https") ? 443 : 80) : origin.getPort();
                            int nextPort = next.getPort() < 0
                                    ? (next.getScheme().equals("https") ? 443 : 80) : next.getPort();
                            if (!origin.getScheme().equals(next.getScheme())
                                    || !origin.getHost().equalsIgnoreCase(next.getHost())
                                    || oldPort != nextPort) {
                                redirectStop = "跨来源重定向已停止，避免将完整请求中的凭证转发到其他来源";
                            } else {
                                boolean get = status == 303 && !current.method().equals("HEAD")
                                        || (status == 301 || status == 302) && current.method().equals("POST");
                                var redirectHeaders = get
                                        ? current.headers().stream()
                                                .filter(h -> !h.name().equalsIgnoreCase("Content-Type")
                                                        && !h.name().equalsIgnoreCase("Content-Length"))
                                                .toList()
                                        : current.headers();
                                current = new RequestSpec(get ? "GET" : current.method(), next.toString(),
                                        redirectHeaders, get ? new byte[0] : current.bodyBytes(),
                                        true, current.timeoutMillis());
                                continue;
                            }
                        }
                    }
                    received = new ByteArrayOutputStream();
                    contentType = response.header("Content-Type");
                    charset = response.charset() == null ? StandardCharsets.UTF_8.name() : response.charset();
                    // Hutool synthesizes an error message when the real HTTP error stream is absent.
                    var activeConnection = connection.get();
                    var input = status >= 400 && activeConnection != null && activeConnection.getErrorStream() == null
                            ? null : response.bodyStream();
                    if (input != null) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            int remainingBytes = MAX_RESPONSE_BYTES - received.size();
                            if (read > remainingBytes) {
                                received.write(buffer, 0, remainingBytes);
                                return result("unknown", status, start, "响应超过 1 MiB，结果待确认",
                                        capture(received, contentType, charset, false, true));
                            }
                            received.write(buffer, 0, read);
                            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > timeout)
                                return result("unknown", status, start, timeoutSummary(phase, timeout),
                                        capture(received, contentType, charset, false, false));
                        }
                    }
                    if (expired.get())
                        return result("unknown", status, start, timeoutSummary(phase, timeout),
                                capture(received, contentType, charset, false, false));
                    var captured = capture(received, contentType, charset, true, false);
                    if (redirectStop != null) return result("unknown", status, start, redirectStop, captured);
                    String classification =
                            rules.classify(status, received.toString(StandardCharsets.UTF_8));
                    String summary =
                            switch (classification) {
                                case "success" -> "命中成功规则";
                                case "already_done" -> "命中已完成规则";
                                case "expired" -> "凭证过期，请更新 cURL";
                                case "failed" -> "HTTP 或业务规则判定失败";
                                default -> "响应未命中可确认规则，请核对平台实际状态";
                            };
                    return result(classification, status, start, summary, captured);
                } finally {
                    if (watchdog != null) watchdog.cancel(false);
                }
            }
            return result("unknown", null, start, "重定向未完成，结果待确认");
        } catch (com.signdesk.common.ApiException e) {
            return result("failed", null, start, e.getMessage(),
                    capture(received, contentType, charset, false, false));
        } catch (Exception e) {
            return result("unknown", httpStatus, start,
                    expired.get() ? timeoutSummary(phase, timeout) : networkSummary(e, phase, timeout),
                    capture(received, contentType, charset, false, false));
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            var c = connection.get();
            if (c != null) c.disconnect();
        }
    }

    private static String timeoutSummary(String phase, int timeout) {
        return phase + "：达到执行期限（TIMEOUT，有效上限 " + timeout + " ms）；"
                + "请检查代理和超时设置；结果待确认，请核对平台后再执行";
    }

    private static String networkSummary(Exception error, String phase, int timeout) {
        String reason = "网络异常（NETWORK）";
        // Only fixed descriptions and exception types; exception messages can contain credentials.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException)
                return timeoutSummary(phase, timeout);
            if (cause instanceof java.security.cert.CertificateException
                    || cause instanceof javax.net.ssl.SSLPeerUnverifiedException) {
                reason = "证书或主机名校验失败（TLS_CERTIFICATE）";
                break;
            }
            if (cause instanceof javax.net.ssl.SSLException)
                reason = "TLS 握手或连接失败（TLS）";
            else if (cause instanceof java.net.UnknownHostException)
                reason = "目标或代理主机无法解析（DNS）";
            else if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.NoRouteToHostException)
                reason = "无法连接目标或代理（CONNECT）";
        }
        return phase + "：" + reason + "；请检查网络和代理；结果待确认，请核对平台后再执行";
    }

    private static ResponseBody capture(
            ByteArrayOutputStream bytes, String contentType, String charset,
            boolean complete, boolean truncated) {
        return bytes == null ? null : new ResponseBody(bytes.toByteArray(), contentType, charset, complete, truncated);
    }

    private Result result(String status, Integer http, long start, String text) {
        return result(status, http, start, text, null);
    }

    private Result result(String status, Integer http, long start, String text, ResponseBody response) {
        return new Result(status, http, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), text, response);
    }

    @Override
    public void close() {
        deadlines.shutdownNow();
    }
}
