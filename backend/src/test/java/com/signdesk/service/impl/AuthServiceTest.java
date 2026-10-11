package com.signdesk.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.common.ApiException;
import com.signdesk.config.AuthProperties;
import com.signdesk.domain.bo.LoginBo;

import org.junit.jupiter.api.Test;

import java.time.*;

class AuthServiceTest {
    private static final LoginBo VALID = new LoginBo("fixture-admin", "fixture-password");
    private static final LoginBo INVALID = new LoginBo("fixture-admin", "incorrect-fixture");

    AuthServiceImpl service(Clock clock) {
        var properties = new AuthProperties();
        properties.setUsername(VALID.username());
        properties.setPassword(VALID.password());
        assertFalse(properties.toString().contains(VALID.password()));
        return new AuthServiceImpl(properties, clock);
    }

    @Test
    void onlyConfiguredCredentialsWorkWithoutTrimmingOrExposingTheWrongField() {
        var service = service(Clock.systemUTC());
        assertEquals(VALID.username(), service.authenticate(VALID, "fixture-address"));
        for (var input : java.util.List.of(INVALID, new LoginBo("other-user", VALID.password()),
                new LoginBo(VALID.username(), " " + VALID.password()))) {
            var error = assertThrows(ApiException.class, () -> service.authenticate(input, "fixture-address"));
            assertEquals(401, error.status());
            assertEquals("用户名或密码错误", error.getMessage());
        }
        assertEquals(VALID.username(), service.authenticate(VALID, "fixture-address"));
    }

    @Test
    void fiveFailuresLimitTheSourceUntilTheExactWindowBoundary() {
        var clock = new MutableClock();
        var service = service(clock);
        for (int i = 0; i < 5; i++)
            assertEquals(401, assertThrows(ApiException.class, () -> service.authenticate(INVALID, "source-one")).status());
        assertEquals(429, assertThrows(ApiException.class, () -> service.authenticate(VALID, "source-one")).status());
        assertEquals(VALID.username(), service.authenticate(VALID, "source-two"));
        clock.now = clock.now.plusSeconds(59);
        assertEquals(429, assertThrows(ApiException.class, () -> service.authenticate(VALID, "source-one")).status());
        clock.now = clock.now.plusSeconds(1);
        assertEquals(VALID.username(), service.authenticate(VALID, "source-one"));
    }

    @Test
    void successfulLoginResetsTheFailureCounter() {
        var service = service(Clock.systemUTC());
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++)
                assertEquals(401, assertThrows(ApiException.class, () -> service.authenticate(INVALID, "source")).status());
            assertEquals(VALID.username(), service.authenticate(VALID, "source"));
        }
    }

    @Test
    void sourceBookkeepingIsBoundedAndExpiredSlotsBecomeAvailable() {
        var clock = new MutableClock();
        var service = service(clock);
        for (int i = 0; i < 1024; i++) {
            String address = "fixture-source-" + i;
            assertEquals(401, assertThrows(ApiException.class, () -> service.authenticate(INVALID, address)).status());
        }
        assertEquals(429, assertThrows(ApiException.class, () -> service.authenticate(VALID, "new-source")).status());
        clock.now = clock.now.plusSeconds(60);
        assertEquals(VALID.username(), service.authenticate(VALID, "new-source"));
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
