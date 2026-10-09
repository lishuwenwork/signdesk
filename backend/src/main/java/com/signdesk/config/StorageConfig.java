package com.signdesk.config;

import com.signdesk.storage.DatabaseInitializer;
import com.signdesk.storage.InstanceLock;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import javax.sql.DataSource;

@Configuration
public class StorageConfig {
    @Bean(destroyMethod = "close")
    InstanceLock instanceLock(@Value("${signdesk.data-dir}") String directory) throws Exception {
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return new InstanceLock(dir);
    }

    @Bean
    DatabaseInitializer databaseInitializer(InstanceLock lock) {
        return new DatabaseInitializer(lock.directory().resolve("signdesk.db"));
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(DatabaseInitializer initializer) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + initializer.database());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("busy_timeout", "5000");
        config.addDataSourceProperty("journal_mode", "WAL");
        return new HikariDataSource(config);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("schedule-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(
                e ->
                        org.slf4j.LoggerFactory.getLogger(StorageConfig.class)
                                .error(
                                        "Scheduler operation failed ({})",
                                        e.getClass().getSimpleName()));
        return scheduler;
    }
}
