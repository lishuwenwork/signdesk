package com.signdesk.config;

import com.signdesk.common.Json;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** No application login; use a private ingress. Reject browser cross-site management writes. */
@Component
public class RequestBoundary extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            if (request.getContentLengthLong() > 12582912) {
                reject(response, 413, "请求数据过大");
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
                        int port =
                                uri.getPort() < 0
                                        ? (uri.getScheme().equals("https") ? 443 : 80)
                                        : uri.getPort();
                        if (!uri.getHost().equalsIgnoreCase(request.getServerName())
                                || port != request.getServerPort()) {
                            reject(response, 403, "请求来源与服务地址不一致");
                            return;
                        }
                    } catch (Exception e) {
                        reject(response, 403, "请求来源不合法");
                        return;
                    }
                }
                if (type == null || !type.toLowerCase().startsWith("application/json")) {
                    reject(response, 415, "管理写接口只接受 application/json");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.write(Map.of("message", message)));
    }
}
