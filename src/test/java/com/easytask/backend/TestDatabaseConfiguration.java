package com.easytask.backend;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Integration tests run against the dedicated local {@code easytask_test} database
 * (create once with: {@code sudo -u postgres psql -c "CREATE DATABASE easytask_test OWNER easytask;"}).
 * The schema is dropped and re-migrated once per test context so every run starts clean.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfiguration {

    @Bean
    FlywayMigrationStrategy cleanMigrateStrategy() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
