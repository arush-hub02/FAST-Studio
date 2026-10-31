package com.faststudio.v1_0.config;

import com.faststudio.v1_0.core.tenant.TenantAwareDataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Two connection pools to the same database:
 * - the app pool connects as the database owner but immediately switches to the restricted role
 *   (default faststudio_app, created by migration V2) and is wrapped in {@link TenantAwareDataSource},
 *   so every query runs under PostgreSQL row-level security;
 * - a small migration pool keeps owner rights so Flyway can create tables, roles and policies.
 * spring.datasource.url=embedded (default) starts a real PostgreSQL in-process with data in data/pg.
 */
@Configuration
public class DatabaseConfig {

    public record Target(String url, String user, String password) {}

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "spring.datasource.url", havingValue = "embedded")
    EmbeddedPostgres embeddedPostgres(@Value("${faststudio.data-dir}") String dataDir,
                                      @Value("${faststudio.db.port:54329}") int port) throws IOException {
        Path pg = Path.of(dataDir, "pg").toAbsolutePath();
        Files.createDirectories(pg.getParent());
        EmbeddedPostgres.Builder b = EmbeddedPostgres.builder().setDataDirectory(pg).setCleanDataDirectory(false);
        if (port > 0) b.setPort(port);      // 0 = any free port (tests)
        return b.start();
    }

    @Bean
    Target databaseTarget(@Value("${spring.datasource.url}") String url, @Value("${spring.datasource.username}") String user,
                          @Value("${spring.datasource.password}") String password, ObjectProvider<EmbeddedPostgres> embedded) {
        if ("embedded".equals(url)) return new Target(embedded.getObject().getJdbcUrl("postgres", "postgres"), "postgres", "");
        return new Target(url, user, password);
    }

    @Bean
    @Primary
    DataSource dataSource(Target t, @Value("${faststudio.db.app-role:faststudio_app}") String appRole) {
        HikariDataSource ds = pool(t, "app", 10);
        if (!appRole.isBlank()) {
            if (!appRole.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Invalid faststudio.db.app-role");
            ds.setConnectionInitSql("SET ROLE " + appRole);
        }
        return new TenantAwareDataSource(ds);
    }

    @Bean
    @FlywayDataSource
    DataSource migrationDataSource(Target t) {
        HikariDataSource ds = pool(t, "migrations", 2);
        ds.setMinimumIdle(0);
        return ds;
    }

    private static HikariDataSource pool(Target t, String name, int size) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("fast-studio-" + name);
        ds.setJdbcUrl(t.url());
        ds.setUsername(t.user());
        ds.setPassword(t.password());
        ds.setMaximumPoolSize(size);
        return ds;
    }
}
