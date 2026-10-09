package com.signdesk.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.config.StorageConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.*;
import java.util.Map;

import javax.sql.DataSource;

class DatabaseInitializerTest {
    @TempDir Path directory;

    JdbcTemplate jdbc(Path database) {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + database);
        return new JdbcTemplate(source);
    }

    @Test
    void initializesTheOnlyPlainV1AndPreservesDataOnRepeatedStartup() throws Exception {
        Path database = directory.resolve("signdesk.db");
        new DatabaseInitializer(database);
        var jdbc = jdbc(database);
        assertEquals(
                "signdesk-plain-v1",
                jdbc.queryForObject("SELECT format FROM schema_info", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM schema_info", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE name='schema_migrations'",
                        Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT concurrency FROM settings", Integer.class));
        assertEquals(
                "system", jdbc.queryForObject("SELECT proxy_mode FROM settings", String.class));
        jdbc.update("INSERT INTO platforms(id,name) VALUES('123','既有平台')");
        jdbc.update("UPDATE settings SET timeout_seconds=60,version=9");
        new DatabaseInitializer(database);
        new DatabaseInitializer(database);
        assertEquals("既有平台", jdbc.queryForObject("SELECT name FROM platforms", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT version FROM settings", Integer.class));
        assertEquals(
                60, jdbc.queryForObject("SELECT timeout_seconds FROM settings", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM run_responses", Integer.class));
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().endsWith(".key")));
        }
    }

    @Test
    void refusesOldEncryptedDatabaseWithoutChangingAnyBytesOrCreatingWal() throws Exception {
        Path database = directory.resolve("legacy.db");
        var jdbc = jdbc(database);
        jdbc.execute(
                "CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,applied_at TEXT NOT"
                    + " NULL)");
        jdbc.update("INSERT INTO schema_migrations VALUES(4,'fixture')");
        jdbc.execute(
                "CREATE TABLE request_revisions(request_id TEXT,revision INTEGER,ciphertext TEXT)");
        jdbc.update("INSERT INTO request_revisions VALUES('old',1,'old-encrypted-fixture')");
        byte[] before = Files.readAllBytes(database);
        var error =
                assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(database));
        assertTrue(error.getMessage().contains("signdesk-plain-v1"));
        assertArrayEquals(before, Files.readAllBytes(database));
        assertFalse(Files.exists(Path.of(database + "-wal")));
        assertFalse(Files.exists(Path.of(database + "-shm")));
        assertEquals(
                4, jdbc.queryForObject("SELECT version FROM schema_migrations", Integer.class));
    }

    @Test
    void rejectsIncompleteNewSchemaAndNeverRepairsIt() throws Exception {
        Path database = directory.resolve("incomplete.db");
        new DatabaseInitializer(database);
        var jdbc = jdbc(database);
        jdbc.execute("DROP INDEX request_active_once");
        byte[] before = Files.readAllBytes(database);
        assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(database));
        assertArrayEquals(before, Files.readAllBytes(database));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE name='request_active_once'",
                        Integer.class));
    }

    @Test
    void rejectsUnknownTablesAndFalseMarkersWithoutChangingTheFile() throws Exception {
        Path database = directory.resolve("unknown.db");
        var jdbc = jdbc(database);
        jdbc.execute(
                "CREATE TABLE schema_info(id INTEGER PRIMARY KEY,format TEXT,version INTEGER)");
        jdbc.update("INSERT INTO schema_info VALUES(1,'signdesk-plain-v1',1)");
        jdbc.execute("CREATE TABLE unrelated(user_value TEXT)");
        byte[] before = Files.readAllBytes(database);
        assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(database));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void refusesGarbageAndMalformedMarkersWithoutOverwritingThem() throws Exception {
        Path garbage = directory.resolve("garbage.db");
        Files.writeString(garbage, "fixture-not-sqlite");
        byte[] bytes = Files.readAllBytes(garbage);
        assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(garbage));
        assertArrayEquals(bytes, Files.readAllBytes(garbage));
        Path database = directory.resolve("marker.db");
        new DatabaseInitializer(database);
        jdbc(database).update("DELETE FROM schema_info");
        byte[] before = Files.readAllBytes(database);
        assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(database));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void realDataSourceLocksBeforeInitializationThenEnablesWalForeignKeysAndBusyTimeout()
            throws Exception {
        try (var context = context(directory)) {
            var source = context.getBean(DataSource.class);
            assertEquals(1, ((HikariDataSource) source).getMaximumPoolSize());
            var jdbc = new JdbcTemplate(source);
            assertEquals("wal", jdbc.queryForObject("PRAGMA journal_mode", String.class));
            assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class));
            assertEquals(5000, jdbc.queryForObject("PRAGMA busy_timeout", Integer.class));
            assertThrows(IllegalStateException.class, () -> new InstanceLock(directory));
            assertThrows(
                    Exception.class,
                    () ->
                            jdbc.update(
                                    "INSERT INTO accounts(id,platform_id,alias)"
                                        + " VALUES('2','missing','fixture')"));
            assertThrows(Exception.class, () -> jdbc.update("INSERT INTO settings(id) VALUES(2)"));
        }
        try (var context = context(directory)) {
            assertNotNull(context.getBean(DatabaseInitializer.class));
        }
    }

    @Test
    void actualStartupRejectsOldDatabaseBeforePoolCanSetWal() throws Exception {
        Path database = directory.resolve("signdesk.db");
        jdbc(database).execute("CREATE TABLE old_table(value TEXT)");
        byte[] before = Files.readAllBytes(database);
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "fixture", Map.of("signdesk.data-dir", directory.toString())));
        context.register(StorageConfig.class);
        try {
            assertThrows(Exception.class, context::refresh);
        } finally {
            context.close();
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        assertFalse(Files.exists(Path.of(database + "-wal")));
        assertFalse(Files.exists(Path.of(database + "-shm")));
    }

    AnnotationConfigApplicationContext context(Path dir) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "fixture", Map.of("signdesk.data-dir", dir.toString())));
        context.register(StorageConfig.class);
        context.refresh();
        return context;
    }
}
