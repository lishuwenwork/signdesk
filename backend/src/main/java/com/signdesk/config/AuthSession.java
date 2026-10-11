package com.signdesk.config;

import com.signdesk.domain.vo.AuthSessionVo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseCookie;

/** Servlet session state only; it is never part of business storage or backups. */
public final class AuthSession {
    private static final String USERNAME = AuthSession.class.getName() + ".username";

    private AuthSession() {}

    public static AuthSessionVo current(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return new AuthSessionVo(false, null);
        try {
            var username = session.getAttribute(USERNAME);
            return username instanceof String value
                    ? new AuthSessionVo(true, value)
                    : new AuthSessionVo(false, null);
        } catch (IllegalStateException e) {
            // A concurrent logout may have invalidated the session.
            return new AuthSessionVo(false, null);
        }
    }

    public static AuthSessionVo login(HttpServletRequest request, String username) {
        var session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(USERNAME, username);
        return new AuthSessionVo(true, username);
    }

    public static void logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // Logout is idempotent, including concurrent requests.
            }
        }
        var config = request.getServletContext().getSessionCookieConfig();
        String path = config.getPath();
        if (path == null || path.isEmpty()) {
            path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        }
        var cookie = ResponseCookie.from(config.getName() == null ? "JSESSIONID" : config.getName(), "")
                .path(path)
                .httpOnly(true)
                .secure(config.isSecure() || request.isSecure())
                .sameSite("Lax")
                .maxAge(0);
        if (config.getDomain() != null) cookie.domain(config.getDomain());
        response.addHeader("Set-Cookie", cookie.build().toString());
    }
}
