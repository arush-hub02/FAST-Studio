package com.faststudio.v1.repositories.ads;

import com.faststudio.v1.config.DatabaseLogins;
import com.faststudio.v1.core.common.Secrets;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V28 moves a project's ad waterfall into instances of the direct and vast connectors: ids, order, sealed tags and owner kept. */
class AdServerMigrationTest {
    @Test
    void waterfallMovesIntoConnections() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            Secrets secrets = new Secrets("", Files.createTempDirectory("ad-keys").toString());
            String url = pg.getJdbcUrl("postgres", "postgres");
            Flyway.configure().dataSource(url, "postgres", "").callbacks(new DatabaseLogins(secrets)).target("27").load().migrate();
            JdbcTemplate db = new JdbcTemplate(pg.getPostgresDatabase());
            UUID org = UUID.randomUUID(), direct = UUID.randomUUID(), gam = UUID.randomUUID(), own = UUID.randomUUID();
            db.update("insert into organization(id, slug, name, country, timezone) values (?, 'ads', 'Org', 'IN', 'UTC')", org);
            db.update("insert into ad_source(id, org_id, name, kind, position) values (?, ?, 'Direct', 'DIRECT', 5)", direct, org);
            db.update("insert into ad_source(id, org_id, name, kind, tag_url, timeout_ms, position, enabled) values (?, ?, 'GAM', 'VAST', 'sealed-gam', 1500, 2, false)", gam, org);
            db.update("insert into ad_source(id, org_id, name, kind, tag_url, timeout_ms, position, managed_by) values (?, ?, 'Own', 'VAST', 'sealed-own', 800, 9, 'TENANT')", own, org);

            Flyway.configure().dataSource(url, "postgres", "").callbacks(new DatabaseLogins(secrets)).load().migrate();

            assertEquals("1 vast GAM false PLATFORM|2 direct Direct true PLATFORM|3 vast Own true TENANT", db.queryForObject("""
                    select string_agg(position || ' ' || connector_key || ' ' || name || ' ' || enabled || ' ' || managed_by, '|' order by position)
                    from connector_instance where org_id = ?""", String.class, org), "order kept as 1..n, owner and on/off kept");
            assertEquals(gam, db.queryForObject("select id from connector_instance where name = 'GAM'", UUID.class), "ids kept");
            assertEquals("tag_url=sealed-gam,timeout_ms=1500", db.queryForObject("""
                    select string_agg(property_key || '=' || value, ',' order by property_key) from connector_value where instance_id = ?""", String.class, gam),
                    "sealed tag copied unchanged");
            assertEquals(0, db.queryForObject("select count(*) from information_schema.tables where table_name = 'ad_source'", Integer.class));
            assertThrows(Exception.class, () -> db.update("insert into connector_instance(connector_key, org_id, name, position) values ('direct', ?, 'Second', 4)", org),
                    "one direct-sold source per project");
        }
    }
}
