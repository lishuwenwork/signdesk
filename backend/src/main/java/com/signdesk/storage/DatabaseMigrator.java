package com.signdesk.storage;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.sql.Connection;

import javax.sql.DataSource;

@Component
public class DatabaseMigrator {
    public DatabaseMigrator(DataSource source, JdbcTemplate jdbc) throws Exception {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,"
                        + " applied_at TEXT NOT NULL)");
        Integer version =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version),0) FROM schema_migrations", Integer.class);
        if (version > 3)
            throw new IllegalStateException("Database schema is newer than this application");
        for (int next = version + 1; next <= 3; next++) {
            try (Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V" + next + ".sql"));
                    connection
                            .createStatement()
                            .executeUpdate(
                                            "INSERT INTO schema_migrations"
                                                    + " VALUES(" + next + ",strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        }
    }
}
