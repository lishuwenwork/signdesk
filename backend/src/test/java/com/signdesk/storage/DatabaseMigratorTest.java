package com.signdesk.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

class DatabaseMigratorTest {
    @TempDir Path directory;

    @Test
    void upgradesV3WithoutInventingBodiesForHistoricalRuns() throws Exception {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("v3.db"));
        var jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            for (int version = 1; version <= 3; version++)
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V" + version + ".sql"));
        }
        jdbc.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        jdbc.update("INSERT INTO schema_migrations VALUES(3,'fixture')");
        jdbc.update("INSERT INTO platforms(id,name) VALUES('123','平台')");
        new DatabaseMigrator(source, jdbc);
        new DatabaseMigrator(source, jdbc);
        assertEquals(4, jdbc.queryForObject("SELECT MAX(version) FROM schema_migrations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM run_responses", Integer.class));
        assertEquals("平台", jdbc.queryForObject("SELECT name FROM platforms", String.class));
    }

    @Test
    void upgradesV2AndPreservesTemplatesOnRepeatedStartup() throws Exception {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("v2.db"));
        var jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V1.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V2.sql"));
        }
        jdbc.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        jdbc.update("INSERT INTO schema_migrations VALUES(2,'fixture')");
        jdbc.update("INSERT INTO platforms(id,name) VALUES('123','平台')");
        new DatabaseMigrator(source, jdbc);
        jdbc.update("INSERT INTO request_templates(id,platform_id,name,rules_json) VALUES('456','123','签到','{}')");
        new DatabaseMigrator(source, jdbc);
        assertEquals(4, jdbc.queryForObject("SELECT MAX(version) FROM schema_migrations", Integer.class));
        assertEquals("签到", jdbc.queryForObject("SELECT name FROM request_templates", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM request_templates", Integer.class));
    }

    @Test
    void upgradesExistingV1DataAndCanBeRunAgain() throws Exception {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("legacy.db"));
        var jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V1.sql"));
        }
        jdbc.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        jdbc.update("INSERT INTO schema_migrations VALUES(1,'fixture')");
        jdbc.update("INSERT INTO platforms(id,name) VALUES('existing','既有平台')");
        jdbc.update("UPDATE settings SET timeout_seconds=60,version=9");
        new DatabaseMigrator(source, jdbc);
        new DatabaseMigrator(source, jdbc);
        assertEquals(4, jdbc.queryForObject("SELECT MAX(version) FROM schema_migrations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM request_templates", Integer.class));
        assertEquals("既有平台", jdbc.queryForObject("SELECT name FROM platforms", String.class));
        var settings = jdbc.queryForMap("SELECT * FROM settings");
        assertEquals(60, settings.get("timeout_seconds"));
        assertEquals(9, settings.get("version"));
        assertEquals("system", settings.get("proxy_mode"));
        assertEquals("", settings.get("proxy_host"));
        assertEquals(0, settings.get("proxy_port"));
    }
}
