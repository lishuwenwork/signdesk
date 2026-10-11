package com.signdesk.service.impl;

import com.signdesk.common.ApiException;
import com.signdesk.config.AuthProperties;
import com.signdesk.domain.bo.LoginBo;
import com.signdesk.service.IAuthService;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthServiceImpl implements IAuthService {
    private static final int MAX_FAILURES = 5;
    private static final int MAX_SOURCES = 1024;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final String username;
    private final byte[] usernameDigest;
    private final byte[] passwordDigest;
    private final Clock clock;
    private final Map<String, Failures> failures = new HashMap<>();

    public AuthServiceImpl(AuthProperties properties, Clock clock) {
        this.username = properties.getUsername();
        this.usernameDigest = digest(username);
        this.passwordDigest = digest(properties.getPassword());
        this.clock = clock;
    }

    @Override
    public synchronized String authenticate(LoginBo input, String remoteAddress) {
        Instant now = clock.instant();
        failures.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        Failures previous = failures.get(remoteAddress);
        if ((previous != null && previous.count() >= MAX_FAILURES)
                || (previous == null && failures.size() >= MAX_SOURCES)) {
            throw new ApiException(429, "登录尝试过于频繁，请稍后再试");
        }
        // Evaluate both fixed-length comparisons, without exposing which credential was wrong.
        boolean valid = MessageDigest.isEqual(usernameDigest, digest(input.username()))
                & MessageDigest.isEqual(passwordDigest, digest(input.password()));
        if (!valid) {
            failures.put(remoteAddress, new Failures(
                    previous == null ? 1 : previous.count() + 1,
                    previous == null ? now.plus(WINDOW) : previous.expiresAt()));
            throw new ApiException(401, "用户名或密码错误");
        }
        failures.remove(remoteAddress);
        return username;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record Failures(int count, Instant expiresAt) {}
}
