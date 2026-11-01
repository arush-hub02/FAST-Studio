package com.faststudio.v1.config;

import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.tenant.TenantAwareDataSource;

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
 * Three connection pools to the same database, behind one {@link TenantAwareDataSource}:
 * - the app pool logs in as faststudio_app_login (member of faststudio_app only): every request query runs under
 *   PostgreSQL row-level security for the organization stamped on the connection, and cannot switch role;
 * - the system pool logs in as faststudio_system_login (member of faststudio_system): used only inside
 *   TenantScope.bypass for cross-tenant system work, which the row policies allow for that login (migration V18);
 * - a small migration pool keeps the configured owner credentials so Flyway can create tables, roles and policies,
 *   and so {@link DatabaseLogins} can maintain the two login roles. Requests never use it.
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
    DataSource dataSource(Target t, Secrets secrets) {
        return new TenantAwareDataSource(pool(DatabaseLogins.login(t, DatabaseLogins.APP, secrets), "app", 10),
                pool(DatabaseLogins.login(t, DatabaseLogins.SYSTEM, secrets), "system", 10));   // also serves viewer session lookups and platform admin requests
    }

    @Bean
    DatabaseLogins databaseLogins(Secrets secrets) { return new DatabaseLogins(secrets); }

    @Bean
    @FlywayDataSource
    DataSource migrationDataSource(Target t) {
        HikariDataSource ds = pool(t, "migrations", 3);
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
