package com.signdesk.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.common.*;
import com.signdesk.domain.bo.BackupPreviewBo;
import com.signdesk.domain.vo.BackupFile;
import com.signdesk.engine.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

class BackupValidationTest {
    private final BackupValidator validator = new BackupValidator(new CurlParser());

    BackupFile file(String value) {
        var settings =
                new BackupFile.Settings(true, 2, 20, 30, 1, new BackupFile.Proxy("system", "", 0));
        var platform = new BackupFile.PlatformEntry("1", "平台", "", true, 0);
        var account = new BackupFile.AccountEntry("2", "1", "账号", true, 0);
        var request =
                new BackupFile.RequestEntry(
                        "3",
                        "2",
                        "请求",
                        true,
                        false,
                        0,
                        new ResultRules(
                                new ResultRules.Match("code", value, null), null, null, null),
                        null);
        var schedule =
                new BackupFile.ScheduleEntry(
                        "1",
                        new BackupFile.Schedule(
                                false,
                                "daily",
                                List.of(1, 2, 3, 4, 5, 6, 7),
                                List.of("09:00"),
                                "Asia/Shanghai",
                                2,
                                0,
                                true,
                                1));
        return new BackupFile(
                "signdesk-plain-v1",
                new BackupFile.Payload(
                        false,
                        settings,
                        List.of(platform),
                        List.of(account),
                        List.of(request),
                        List.of(),
                        List.of(schedule),
                        List.of(),
                        List.of()));
    }

    @Test
    void payloadLimitCountsUtf8BytesAndAcceptsExactlyEightMiB() {
        int base = Json.write(file("").payload()).getBytes(StandardCharsets.UTF_8).length;
        int remaining = BackupValidator.PAYLOAD_LIMIT - base;
        String multibyte = "汉".repeat(remaining / 3) + "a".repeat(remaining % 3);
        var exact = file(multibyte);
        assertEquals(8388608, Json.write(exact.payload()).getBytes(StandardCharsets.UTF_8).length);
        assertTrue(Json.write(exact.payload()).length() < 8388608);
        assertNotNull(validator.validate(exact));
        var error =
                assertThrows(ApiException.class, () -> validator.validate(file(multibyte + "a")));
        assertTrue(error.getMessage().contains("8 MiB"));
    }

    @Test
    void allRelationsSchedulesAndDayMarkersAreValidatedBeforePersistence() {
        var original = file("fixture");
        for (String group : List.of("accounts", "requests", "schedules")) {
            var map = Json.map(Json.write(original));
            var payload = (Map<String, Object>) map.get("payload");
            var row = ((List<Map<String, Object>>) payload.get(group)).getFirst();
            row.put(group.equals("requests") ? "accountId" : "platformId", "999");
            assertThrows(ApiException.class, () -> validator.validate(decode(map)));
        }
        var payload = original.payload();
        var completed = List.of(new BackupFile.DayMarker("3", "2026-10-09"));
        var wrongMode =
                new BackupFile(
                        original.format(),
                        new BackupFile.Payload(
                                false,
                                payload.settings(),
                                payload.platforms(),
                                payload.accounts(),
                                payload.requests(),
                                payload.templates(),
                                payload.schedules(),
                                completed,
                                List.of()));
        assertThrows(ApiException.class, () -> validator.validate(wrongMode));
        var fullRequest =
                new BackupFile.RequestEntry(
                        "3",
                        "2",
                        "请求",
                        true,
                        false,
                        0,
                        ResultRules.defaults(),
                        "curl 'https://example.invalid/'");
        for (var markers :
                List.of(
                        List.of(new BackupFile.DayMarker("999", "2026-10-09")),
                        List.of(new BackupFile.DayMarker("3", "2026-02-30")),
                        List.of(
                                new BackupFile.DayMarker("3", "2026-10-09"),
                                new BackupFile.DayMarker("3", "2026-10-09")))) {
            var invalid =
                    new BackupFile(
                            original.format(),
                            new BackupFile.Payload(
                                    true,
                                    payload.settings(),
                                    payload.platforms(),
                                    payload.accounts(),
                                    List.of(fullRequest),
                                    List.of(),
                                    payload.schedules(),
                                    markers,
                                    List.of()));
            assertThrows(ApiException.class, () -> validator.validate(invalid));
        }
    }

    @Test
    void strictCodecRejectsMissingNullCoercedAndUnknownNewFormatFields() {
        for (String field :
                List.of(
                        "includesRequests",
                        "settings",
                        "platforms",
                        "accounts",
                        "requests",
                        "templates",
                        "schedules",
                        "completed",
                        "pending")) {
            var map = Json.map(Json.write(file("fixture")));
            ((Map<String, Object>) map.get("payload")).remove(field);
            assertThrows(ApiException.class, () -> decode(map), field);
        }
        for (Object value : List.of("true", 1)) {
            var map = Json.map(Json.write(file("fixture")));
            ((Map<String, Object>) map.get("payload")).put("includesRequests", value);
            assertThrows(ApiException.class, () -> decode(map));
        }
        var nullPrimitive = Json.map(Json.write(file("fixture")));
        ((Map<String, Object>) nullPrimitive.get("payload")).put("includesRequests", null);
        assertThrows(ApiException.class, () -> decode(nullPrimitive));
        var oldField = Json.map(Json.write(file("fixture")));
        ((Map<String, Object>) oldField.get("payload")).put("formatVersion", 1);
        assertThrows(ApiException.class, () -> decode(oldField));
    }

    @Test
    void configAndFullModesRejectInconsistentCurlPresence() {
        var base = file("fixture");
        var payload = base.payload();
        var request =
                new BackupFile.RequestEntry(
                        "3",
                        "2",
                        "请求",
                        true,
                        false,
                        0,
                        ResultRules.defaults(),
                        "curl 'https://example.invalid/'");
        var configWithCurl =
                new BackupFile(
                        base.format(),
                        new BackupFile.Payload(
                                false,
                                payload.settings(),
                                payload.platforms(),
                                payload.accounts(),
                                List.of(request),
                                List.of(),
                                payload.schedules(),
                                List.of(),
                                List.of()));
        assertThrows(ApiException.class, () -> validator.validate(configWithCurl));
        var fullWithoutCurl =
                new BackupFile(
                        base.format(),
                        new BackupFile.Payload(
                                true,
                                payload.settings(),
                                payload.platforms(),
                                payload.accounts(),
                                payload.requests(),
                                List.of(),
                                payload.schedules(),
                                List.of(),
                                List.of()));
        assertThrows(ApiException.class, () -> validator.validate(fullWithoutCurl));
    }

    @Test
    void strictCodecRejectsNumericNamesFractionalIntegersNullRootsAndTrailingDocuments() {
        var name = Json.map(Json.write(file("fixture")));
        ((List<Map<String, Object>>) ((Map<String, Object>) name.get("payload")).get("platforms"))
                .getFirst()
                .put("name", 123);
        assertThrows(ApiException.class, () -> decode(name));
        var version = Json.map(Json.write(file("fixture")));
        ((Map<String, Object>) ((Map<String, Object>) version.get("payload")).get("settings"))
                .put("version", 1.5);
        assertThrows(ApiException.class, () -> decode(version));
        for (String input : List.of("null", "{} {}", "[]")) {
            assertThrows(
                    ApiException.class,
                    () ->
                            BackupCodec.read(
                                    new ByteArrayInputStream(
                                            input.getBytes(StandardCharsets.UTF_8)),
                                    BackupPreviewBo.class));
        }
    }

    BackupFile decode(Map<String, Object> map) {
        return BackupCodec.read(
                        new ByteArrayInputStream(
                                Json.write(Map.of("backup", map)).getBytes(StandardCharsets.UTF_8)),
                        BackupPreviewBo.class)
                .backup();
    }
}
