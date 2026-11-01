package com.faststudio.v1.core.tenant;

import com.faststudio.v1.config.DatabaseConfig;
import com.faststudio.v1.config.DatabaseLogins;
import com.faststudio.v1.core.common.Secrets;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database-level tenant isolation, using the same wiring as production: migrations as owner (which also maintain the
 * two login roles), requests on the app login and system work on the system login, behind TenantAwareDataSource.
 * Proves that an unscoped query only sees the current tenant, that writes into another tenant are rejected by
 * PostgreSQL itself, that a request connection can't escape into system or owner rights, and that every table
 * carrying an organization is protected.
 */
class RowLevelSecurityTest {
    static EmbeddedPostgres pg;
    static TenantAwareDataSource pools;
    static JdbcTemplate owner, app;
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();
    /** Tables that carry an organization but are read to establish the tenant, belong to a person, or are the platform mail outbox. */
    static final List<String> OUTSIDE_RLS = List.of("email_message", "membership", "organization", "personal_access_token");

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        Secrets secrets = new Secrets("", Files.createTempDirectory("rls-keys").toString());
        var target = new DatabaseConfig.Target(pg.getJdbcUrl("postgres", "postgres"), "postgres", "");
        Flyway.configure().dataSource(target.url(), "postgres", "").callbacks(new DatabaseLogins(secrets)).load().migrate();
        owner = new JdbcTemplate(pg.getPostgresDatabase());
        for (UUID org : new UUID[]{A, B}) {
            owner.update("insert into organization(id, slug, name, country, timezone) values (?,?,?,?,?)", org, "o" + org.toString().substring(0, 8), "Org", "IN", "UTC");
            owner.update("insert into asset(org_id, title, kind) values (?,?,?)", org, "Video of " + org, "MOVIE");
            owner.update("insert into asset(org_id, title, kind) values (?,?,?)", org, "Second of " + org, "MOVIE");
        }
        pools = new TenantAwareDataSource(pool(DatabaseLogins.login(target, DatabaseLogins.APP, secrets)),
                pool(DatabaseLogins.login(target, DatabaseLogins.SYSTEM, secrets)));
        app = new JdbcTemplate(pools);
    }

    static HikariDataSource pool(DatabaseConfig.Target t) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(t.url());
        ds.setUsername(t.user());
        ds.setPassword(t.password());
        ds.setMaximumPoolSize(2);
        return ds;
    }

    @AfterAll
    static void stop() throws Exception {
        pools.close();
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

    @Test
    void aRequestConnectionCannotEscapeItsTenant() {
        TenantScope.as(A, () -> {
            assertEquals(DatabaseLogins.APP, app.queryForObject("select session_user", String.class));
            assertFalse(app.queryForObject("select rolsuper or rolbypassrls from pg_roles where rolname = session_user", Boolean.class));
            // the old switch is gone: setting it changes nothing
            app.queryForObject("select set_config('app.bypass', 'on', false)", String.class);
            assertEquals(2, countAll());
            // no way into the system role, and nothing above the login to fall back to
            assertThrows(Exception.class, () -> app.execute("set role faststudio_system"));
            app.execute("reset role");
            assertEquals(2, countAll());
            assertFalse(app.queryForObject("select fs_system()", Boolean.class));
            return null;
        });
    }

    @Test
    void everyTableWithAnOrganizationIsProtected() {
        List<String> unprotected = owner.queryForList("""
                select distinct c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                join pg_attribute a on a.attrelid = c.oid and not a.attisdropped
                where n.nspname = 'public' and c.relkind in ('r', 'p')
                  and a.attname in ('org_id', 'tenant_id', 'owner_org_id', 'partner_org_id')
                  and (not c.relrowsecurity or not exists (select 1 from pg_policy p where p.polrelid = c.oid))
                order by 1""", String.class);
        assertEquals(OUTSIDE_RLS, unprotected.stream().filter(t -> !t.equals("flyway_schema_history")).toList(),
                "a table that carries an organization needs row-level security and a policy (or a reason to be listed here)");
    }
}
