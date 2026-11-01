package com.faststudio.v1.repositories.media;

import com.faststudio.v1.config.DatabaseLogins;
import com.faststudio.v1.core.common.Secrets;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** V22 turns assets left converting by the old in-memory pools into queued jobs, so they survive the upgrade restart. */
class MediaJobMigrationTest {
    @Test
    void processingAssetsBecomeQueuedJobs() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            Secrets secrets = new Secrets("", Files.createTempDirectory("job-keys").toString());
            String url = pg.getJdbcUrl("postgres", "postgres");
            Flyway.configure().dataSource(url, "postgres", "").callbacks(new DatabaseLogins(secrets)).target("21").load().migrate();
            JdbcTemplate db = new JdbcTemplate(pg.getPostgresDatabase());
            UUID org = UUID.randomUUID();
            db.update("insert into organization(id, slug, name, country, timezone) values (?, 'mig', 'Org', 'IN', 'UTC')", org);
            UUID movie = db.queryForObject("insert into asset(org_id, title, kind, status) values (?, 'Film', 'MOVIE', 'PROCESSING') returning id", UUID.class, org);
            UUID ad = db.queryForObject("insert into asset(org_id, title, kind, status) values (?, 'Spot', 'AD', 'PROCESSING') returning id", UUID.class, org);
            db.update("insert into asset(org_id, title, kind, status) values (?, 'Done', 'MOVIE', 'READY')", org);

            Flyway.configure().dataSource(url, "postgres", "").callbacks(new DatabaseLogins(secrets)).load().migrate();

            assertEquals(2, db.queryForObject("select count(*) from media_job", Integer.class));
            assertEquals("PROGRAMME QUEUED 0", db.queryForObject("select lane || ' ' || state || ' ' || attempts from media_job where asset_id = ?", String.class, movie));
            assertEquals("AD QUEUED 0", db.queryForObject("select lane || ' ' || state || ' ' || attempts from media_job where asset_id = ?", String.class, ad));
        }
    }
}
