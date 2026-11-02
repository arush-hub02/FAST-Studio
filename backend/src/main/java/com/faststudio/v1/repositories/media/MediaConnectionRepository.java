package com.faststudio.v1.repositories.media;

import com.faststudio.v1.repositories.media.MediaJobRepository.Claim;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for a project's video-platform sync state (media_connection, one per project): which provider and the checkpoint of
 * the library sync. Credentials and the health check live in the project's connector connection (V27). Checkpoint writes
 * carry the sync job's fence, so a worker that lost its job can't move the scan.
 */
@Repository
public class MediaConnectionRepository {
    private final JdbcClient db;

    public MediaConnectionRepository(JdbcClient db) { this.db = db; }

    /** Where the library sync stands. startedAt: start of the current/last full scan; finishedAt: when it completed. */
    public record SyncRow(String cursor, int pages, int videos, int added, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}

    /**
     * Makes sure the project has its sync-state row for this provider (credentials live in the project's connector
     * connection since V27). Called when a connection is found, so a library connected from the marketplace can sync.
     */
    public void ensure(UUID org, String provider) {
        db.sql("insert into media_connection(org_id, provider) values (:o, :p) on conflict (org_id) do update set provider = excluded.provider where media_connection.provider <> excluded.provider")
                .param("o", org).param("p", provider).update();
    }

    public Optional<SyncRow> sync(UUID org) {
        return db.sql("""
                select sync_cursor as cursor, sync_pages as pages, sync_videos as videos, sync_added as added,
                       sync_started_at as started_at, sync_finished_at as finished_at
                from media_connection where org_id = :o""").param("o", org).query(SyncRow.class).optional();
    }

    /** A new full scan starts from the first page. */
    public void startSync(UUID org) {
        db.sql("""
                update media_connection set sync_cursor = null, sync_pages = 0, sync_videos = 0, sync_added = 0,
                  sync_started_at = now(), sync_finished_at = null where org_id = :o""").param("o", org).update();
    }

    /** Saves a processed page and where to continue; false when the job is no longer this worker's (nothing changed). */
    public boolean checkpoint(Claim job, String next, int videos, int added) {
        return db.sql("""
                update media_connection set sync_cursor = :n, sync_pages = sync_pages + 1, sync_videos = sync_videos + :v, sync_added = sync_added + :a
                where org_id = :o and exists (select 1 from media_job where id = :j and org_id = :o and fence = :f and state = 'RUNNING')""")
                .param("n", next).param("v", videos).param("a", added).param("o", job.orgId()).param("j", job.id()).param("f", job.fence())
                .update() > 0;
    }

    /**
     * Completes a full scan in one statement: the job succeeds, the scan is marked finished, and every source of this
     * provider not seen since the scan started is flagged MISSING (its local video and media stay). False when the
     * fence no longer matches.
     */
    public boolean completeSync(Claim job, String provider) {
        return db.sql("""
                with j as (update media_job set state = 'SUCCEEDED', finished_at = now(), lease_owner = null, lease_until = null
                           where id = :j and org_id = :o and fence = :f and state = 'RUNNING' returning org_id),
                     c as (update media_connection m set sync_cursor = null, sync_finished_at = now() from j where m.org_id = j.org_id
                           returning m.org_id, m.sync_started_at),
                     s as (update asset_source s set provider_status = 'MISSING' from c
                           where s.org_id = c.org_id and s.provider = :p and s.last_seen_at < c.sync_started_at
                             and s.provider_status not in ('DELETED', 'MISSING'))
                select count(*) from j""")
                .param("j", job.id()).param("o", job.orgId()).param("f", job.fence()).param("p", provider).query(Integer.class).single() > 0;
    }
}
