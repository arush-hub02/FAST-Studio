package com.faststudio.v1.repositories.media;

import com.faststudio.v1.config.DatabaseLogins;
import com.faststudio.v1.core.common.Secrets;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V27 moves a project's video-library credentials into its connector connection, ciphertexts unchanged. */
class MuxConnectorMigrationTest {
    @Test
    void credentialsMoveIntoTheConnection() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            Secrets secrets = new Secrets("", Files.createTempDirectory("mux-keys").toString());
            String url = pg.getJdbcUrl("postgres", "postgres");
            Flyway.configure().dataSource(url, "postgres", "").callbacks(new DatabaseLogins(secrets)).target("26").load().migrate();
            JdbcTemplate db = new JdbcTemplate(pg.getPostgresDatabase());
            UUID org = UUID.randomUUID();
            db.update("insert into organization(id, slug, name, country, timezone) values (?, 'mig', 'Org', 'IN', 'UTC')", org);
            db.update("""
                    insert into media_connection(org_id, provider, token_id, token_secret, webhook_secret, environment, passthrough_tag, checked_at, sync_pages)
                    values (?, 'mux', 'sealed-id', 'sealed-secret', null, 'Production', '', now(), 3)""", org);

            Flyway.configure().dataSource(url, "postgres", "").callbacks(new DatabaseLogins(secrets)).load().migrate();

            assertEquals("environment=Production,token_id=sealed-id,token_secret=sealed-secret", db.queryForObject("""
                    select string_agg(property_key || '=' || value, ',' order by property_key) from connector_value where org_id = ?""", String.class, org),
                    "values copied unchanged; empty ones skipped");
            assertEquals("Mux 0 true", db.queryForObject("""
                    select name || ' ' || position || ' ' || enabled from connector_instance where org_id = ? and connector_key = 'mux'""", String.class, org));
            assertNotNull(db.queryForObject("select checked_at from connector_instance where org_id = ?", Object.class, org), "health check kept");
            assertEquals(3, db.queryForObject("select sync_pages from media_connection where org_id = ?", Integer.class, org), "sync state stays");
            assertEquals(0, db.queryForObject("""
                    select count(*) from information_schema.columns where table_name = 'media_connection' and column_name in ('token_id', 'token_secret')""", Integer.class));
        }
    }
}
