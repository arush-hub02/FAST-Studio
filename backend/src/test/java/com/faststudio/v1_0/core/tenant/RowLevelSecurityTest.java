package com.faststudio.v1_0.core.tenant;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database-level tenant isolation, using the same wiring as production: migrations as owner, app pool switched to
 * the restricted role and wrapped in TenantAwareDataSource. Proves that even an unscoped query ("select ... from asset")
 * only sees the current tenant, and that writes into another tenant are rejected by PostgreSQL itself.
 */
class RowLevelSecurityTest {
    static EmbeddedPostgres pg;
    static HikariDataSource pool;
    static JdbcTemplate owner, app;
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String url = pg.getJdbcUrl("postgres", "postgres");
        Flyway.configure().dataSource(url, "postgres", "").load().migrate();
        owner = new JdbcTemplate(pg.getPostgresDatabase());
        for (UUID org : new UUID[]{A, B}) {
            owner.update("insert into organization(id, slug, name, country, timezone) values (?,?,?,?,?)", org, "o" + org.toString().substring(0, 8), "Org", "IN", "UTC");
            owner.update("insert into asset(org_id, title, kind) values (?,?,?)", org, "Video of " + org, "MOVIE");
            owner.update("insert into asset(org_id, title, kind) values (?,?,?)", org, "Second of " + org, "MOVIE");
        }
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername("postgres");
        pool.setConnectionInitSql("SET ROLE faststudio_app");
        app = new JdbcTemplate(new TenantAwareDataSource(pool));
    }

    @AfterAll
    static void stop() throws Exception {
        pool.close();
        pg.close();
    }

    static int countAll() {
        return app.queryForObject("select count(*) from asset", Integer.class);   // deliberately no org filter
    }

    @Test
    void unscopedQuerySeesOnlyCurrentTenant() {
        assertEquals(2, TenantScope.as(A, RowLevelSecurityTest::countAll));
        assertEquals(0, TenantScope.as(A, () -> app.queryForObject("select count(*) from asset where org_id = ?", Integer.class, B)));
    }

    @Test
    void noTenantMeansNoRows() {
        TenantScope.clear();
        assertEquals(0, countAll());
    }

    @Test
    void cannotWriteIntoAnotherTenant() {
        Exception e = assertThrows(Exception.class, () -> TenantScope.as(A, () ->
                app.update("insert into asset(org_id, title, kind) values (?,?,?)", B, "smuggled", "MOVIE")));
        String cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        assertTrue(cause.contains("row-level security"), cause);
        int updated = TenantScope.as(A, () -> app.update("update asset set title = 'hijacked' where org_id = ?", B));
        assertEquals(0, updated, "rows of another tenant are invisible to UPDATE");
    }

    @Test
    void bypassIsExplicitAndRestoresScope() {
        assertEquals(4, TenantScope.bypass(RowLevelSecurityTest::countAll));
        assertEquals(2, TenantScope.as(B, () -> TenantScope.bypass(RowLevelSecurityTest::countAll) - 2));
        assertNull(TenantScope.org());
        assertFalse(TenantScope.bypassing());
    }

    @Test
    void connectionsDoNotLeakScopeBetweenBorrowers() {
        TenantScope.as(A, RowLevelSecurityTest::countAll);           // pool connection last used for tenant A
        assertEquals(2, TenantScope.as(B, () -> app.queryForObject("select count(*) from asset where org_id = ?", Integer.class, B)));
        assertEquals(0, TenantScope.as(B, () -> app.queryForObject("select count(*) from asset where org_id = ?", Integer.class, A)));
    }
}
