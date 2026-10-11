package com.signdesk.config;

import com.signdesk.common.Json;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Protect management APIs with a session and reject browser cross-site writes. */
@Component
public class RequestBoundary extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        String path = request.getServletPath();
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/api") || path.startsWith("/api/")
                || rawPath.startsWith("/api/") || rawPath.startsWith("/api;")) {
            response.setHeader("Cache-Control", "no-store");
            // Tomcat removes path parameters and decodes paths before MVC mapping. Do not let
            // an alternate spelling bypass the public endpoint whitelist or API protection.
            if (!path.equals(rawPath)) {
                reject(response, 400, "接口路径不合法");
                return;
            }
            if (!isPublic(request.getMethod(), path) && !AuthSession.current(request).authenticated()) {
                reject(response, 401, "请先登录，或登录已过期");
                return;
            }
            int limit = path.equals("/api/auth/login") ? 8192 : 12582912;
            if (request.getContentLengthLong() > limit) {
                reject(response, path.startsWith("/api/backups/") ? 400 : 413,
                        limit == 8192 ? "登录请求数据过大" : "请求数据超过12 MiB");
                return;
            }
            if (!request.getMethod().equals("GET")) {
                String type = request.getContentType(),
                        origin = request.getHeader("Origin"),
                        site = request.getHeader("Sec-Fetch-Site");
                if ("cross-site".equals(site)) {
                    reject(response, 403, "不接受跨站管理请求");
                    return;
                }
                if (origin != null) {
                    try {
                        URI uri = URI.create(origin);
                        int port = uri.getPort() < 0
                                ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                        String scheme = request.isSecure() ? "https" : "http";
                        if (!scheme.equalsIgnoreCase(uri.getScheme())
                                || !request.getServerName().equalsIgnoreCase(uri.getHost())
                                || port != request.getServerPort()) {
                            reject(response, 403, "请求来源与服务地址不一致");
                            return;
                        }
                    } catch (Exception e) {
                        reject(response, 403, "请求来源不合法");
                        return;
                    }
                }
                if (type == null
                        || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                    reject(response, 415, "管理写接口只接受 application/json");
                    return;
                }
                var bytes = new ByteArrayOutputStream();
                var input = request.getInputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - bytes.size()))) != -1) {
                    bytes.write(buffer, 0, read);
                    if (bytes.size() > limit) {
                        reject(response, path.startsWith("/api/backups/") ? 400 : 413,
                                limit == 8192 ? "登录请求数据过大" : "请求数据超过12 MiB");
                        return;
                    }
                }
                request = new BoundedJsonRequest(request, bytes.toByteArray());
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isPublic(String method, String path) {
        return (method.equals("GET") && (path.equals("/api/health") || path.equals("/api/auth/session")))
                || (method.equals("POST") && (path.equals("/api/auth/login") || path.equals("/api/auth/logout")));
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.write(Map.of("message", message)));
    }
}
