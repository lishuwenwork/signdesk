package com.signdesk.common;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.signdesk.controller.*;
import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.engine.*;
import com.signdesk.mapper.RequestRevisionMapper;
import com.signdesk.service.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class BackendArchitectureTest {
    private final Path source = Path.of("src/main/java/com/signdesk");

    @Test
    void servicesContainNoSqlJdbcOrDatabaseRowMapsAndMappersUseBoundXml() throws Exception {
        try (var files = Files.walk(source.resolve("service"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("JdbcTemplate"), file.toString());
                assertFalse(text.contains("java.sql"), file.toString());
                assertFalse(text.contains("Map<String, Object>"), file.toString());
                assertFalse(
                        text.matches(
                                "(?s).*\\b(?:SELECT|INSERT INTO|UPDATE [a-z_]+ SET|DELETE"
                                    + " FROM)\\b.*"),
                        file.toString());
                assertFalse(text.contains("@Scheduled"), file.toString());
            }
        }
        try (var files = Files.walk(Path.of("src/main/resources/mapper"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                assertFalse(Files.readString(file).contains("${"), file.toString());
            }
        }
        try (var files = Files.walk(source.resolve("mapper"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("@Select("), file.toString());
                assertFalse(text.contains("@Update("), file.toString());
            }
        }
    }

    @Test
    void tenBusinessInterfacesAndTypedControllersHaveNoPersistenceDependencies() {
        assertEquals(
                10,
                List.of(
                                IPlatformService.class,
                                IAccountService.class,
                                IRequestService.class,
                                IRequestTemplateService.class,
                                IScheduleService.class,
                                IRunService.class,
                                IRunRecordService.class,
                                ISettingsService.class,
                                IBackupService.class,
                                ISystemService.class)
                        .size());
        for (var controller :
                List.of(
                        PlatformController.class,
                        AccountController.class,
                        RequestController.class,
                        RequestTemplateController.class,
                        ScheduleController.class,
                        RunController.class,
                        SettingsController.class,
                        BackupController.class,
                        SystemController.class)) {
            for (var field : controller.getDeclaredFields()) {
                assertTrue(Modifier.isFinal(field.getModifiers()), field.toString());
                assertTrue(
                        field.getType().getPackageName().equals("com.signdesk.service"),
                        field.toString());
            }
            for (var method : controller.getDeclaredMethods()) {
                assertNotEquals(Object.class, method.getReturnType(), method.toString());
                assertFalse(Map.class.isAssignableFrom(method.getReturnType()), method.toString());
                assertFalse(
                        method.getGenericReturnType()
                                .getTypeName()
                                .contains("com.signdesk.domain.RequestDefinition"));
            }
        }
    }

    @Test
    void compositeKeysDoNotPretendToBeMybatisSingleIdsAndAllEntityFieldsArePrivate() {
        assertFalse(BaseMapper.class.isAssignableFrom(RequestRevisionMapper.class));
        for (var type :
                List.of(RequestRevision.class, DailyCompletion.class, RequestDayState.class)) {
            for (var field : type.getDeclaredFields())
                assertNull(field.getAnnotation(TableId.class));
        }
        for (var type :
                List.of(
                        Platform.class,
                        Account.class,
                        RequestDefinition.class,
                        RequestRevision.class,
                        RequestTemplate.class,
                        PlatformSchedule.class,
                        AppSettings.class,
                        RunBatch.class,
                        RunItem.class,
                        RunResponse.class,
                        DailyCompletion.class,
                        RequestDayState.class)) {
            for (var field : type.getDeclaredFields())
                assertTrue(Modifier.isPrivate(field.getModifiers()), field.toString());
        }
    }

    @Test
    void sensitiveEngineInputOutputAndEntityDiagnosticsNeverIncludePayloads() {
        String secret = "fixture-diagnostic-private";
        var spec =
                new RequestSpec(
                        "POST",
                        "https://example.invalid/" + secret,
                        List.of(new RequestSpec.Header("Cookie", secret)),
                        secret.getBytes(StandardCharsets.UTF_8),
                        false,
                        0);
        var rules = new ResultRules(new ResultRules.Match("code", secret, null), null, null, null);
        var revision = new RequestRevision();
        revision.setRawCurl(secret);
        revision.setSpecJson(secret);
        var response = new RunResponse();
        response.setBodyBytes(secret.getBytes(StandardCharsets.UTF_8));
        var settings = new SettingsVo(true, 2, 20, 30, 1, new ProxySettings("http", secret, 12345));
        for (var value :
                List.of(
                        spec,
                        spec.headers().getFirst(),
                        rules,
                        rules.success(),
                        revision,
                        response,
                        settings,
                        new NewRequestBo(secret, secret, true, rules),
                        new CurlBo(secret, 1),
                        new RuleTestBo(rules, 200, secret),
                        new RevisionVo(secret, spec),
                        new ResponseDetailVo(
                                "complete", secret, "text", "text/plain", "UTF-8", 1, 1048576),
                        new BackupFile.RequestEntry(
                                "1", "2", secret, true, false, 0, rules, secret))) {
            assertFalse(value.toString().contains(secret), value.getClass().getName());
        }
        byte[] first = spec.bodyBytes();
        first[0] = 0;
        assertEquals(secret, new String(spec.bodyBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void theOnlyDatabaseBaselineAndNoLegacyProductionClassesRemain() throws Exception {
        try (var files = Files.list(Path.of("src/main/resources/db"))) {
            assertEquals(
                    List.of("V1.sql"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        for (String file :
                List.of(
                        "common/ApiController.java",
                        "platform/CatalogService.java",
                        "run/RunService.java",
                        "run/SettingsService.java",
                        "schedule/ScheduleService.java",
                        "storage/Db.java",
                        "storage/SecretStore.java",
                        "storage/RunResponseStore.java",
                        "storage/DatabaseMigrator.java"))
            assertFalse(Files.exists(source.resolve(file)));
        String properties = Files.readString(Path.of("src/main/resources/application.yml"));
        assertFalse(properties.contains("SIGNDESK_KEY"));
        assertFalse(properties.contains("SIGNDESK_SECRET"));
        assertTrue(properties.contains("NoLoggingImpl"));
    }
}
