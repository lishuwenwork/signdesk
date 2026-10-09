package com.signdesk.storage;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * A new baseline, never a migrator. Compatibility is probed read-only before any WAL connection.
 */
public final class DatabaseInitializer {
    public static final String FORMAT = "signdesk-plain-v1";
    public static final int VERSION = 1;
    private final Path database;

    public DatabaseInitializer(Path database) {
        this.database = database.toAbsolutePath().normalize();
        try {
            Class.forName("org.sqlite.JDBC");
            boolean empty = !Files.exists(this.database) || Files.size(this.database) == 0;
            if (!empty) {
                var config = new SQLiteConfig();
                config.setReadOnly(true);
                try (Connection connection = config.createConnection(url())) {
                    var schema = schema(connection);
                    empty = schema.isEmpty();
                    if (!empty) validate(connection, schema);
                }
            }
            if (empty) initialize();
        } catch (Exception e) {
            // No statements, values or filesystem credentials are included in the failure.
            throw new IllegalStateException(
                    "数据库不兼容或不完整：仅支持 signdesk-plain-v1/version 1；请选择新的数据目录，不会迁移或重建已有数据库");
        }
    }

    public Path database() {
        return database;
    }

    private String url() {
        return "jdbc:sqlite:" + database;
    }

    private void initialize() throws Exception {
        try (Connection connection = DriverManager.getConnection(url())) {
            connection.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V1.sql"));
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static Map<String, String> schema(Connection connection) throws SQLException {
        var result = new TreeMap<String, String>();
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE"
                                    + " 'sqlite_%' ORDER BY type,name")) {
            while (rows.next()) {
                String sql = rows.getString(3);
                result.put(
                        rows.getString(1) + ":" + rows.getString(2),
                        sql == null ? "" : sql.replaceAll("\\s+", " ").trim());
            }
        }
        return result;
    }

    private static void validate(Connection connection, Map<String, String> actual)
            throws Exception {
        // Compare the complete tables, constraints, FKs and explicit indexes to the only supported
        // V1.
        // A marker alone cannot bless an old or partially-created database.
        try (Connection reference = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ScriptUtils.executeSqlScript(reference, new ClassPathResource("db/V1.sql"));
            if (!schema(reference).equals(actual)) throw new IllegalStateException();
        }
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id,format,version FROM schema_info")) {
            if (!rows.next()
                    || rows.getInt(1) != 1
                    || !FORMAT.equals(rows.getString(2))
                    || rows.getInt(3) != VERSION
                    || rows.next()) throw new IllegalStateException();
        }
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM settings WHERE id=1")) {
            if (!rows.next() || rows.getInt(1) != 1) throw new IllegalStateException();
        }
    }
}
