package com.faststudio.v1.repositories.media;

import com.faststudio.v1.repositories.media.MediaJobRepository.Claim;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for a project's video-platform connection (media_connection, one per project): credentials arrive and leave
 * sealed (this class never decrypts), the last health check, and the checkpoint of the library sync. Checkpoint
 * writes carry the sync job's fence, so a worker that lost its job can't move the scan.
 */
@Repository
public class MediaConnectionRepository {
    private final JdbcClient db;

    public MediaConnectionRepository(JdbcClient db) { this.db = db; }

    /** Sealed credentials as stored, plus the health check. */
    public record Row(String provider, String tokenId, String tokenSecret, String webhookSecret, String environment, String passthroughTag,
                      OffsetDateTime checkedAt, String checkError) {}

    /** Where the library sync stands. startedAt: start of the current/last full scan; finishedAt: when it completed. */
    public record SyncRow(String cursor, int pages, int videos, int added, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}

    public Optional<Row> find(UUID org) {
        return db.sql("""
                select provider, token_id, token_secret, webhook_secret, environment, passthrough_tag, checked_at, check_error
                from media_connection where org_id = :o""")
                .param("o", org).query(Row.class).optional();
    }

    /** Creates or updates the connection; a null sealed value keeps the stored one. New credentials need a new check. */
    public void save(UUID org, String provider, String tokenId, String tokenSecret, String webhookSecret, String environment, String tag) {
        Map<String, Object> p = new HashMap<>(Map.of("o", org, "p", provider, "e", environment, "t", tag));
        p.put("i", tokenId);
        p.put("s", tokenSecret);
        p.put("w", webhookSecret);
        if (db.sql("""
                update media_connection set provider = :p, token_id = coalesce(:i, token_id), token_secret = coalesce(:s, token_secret),
                  webhook_secret = coalesce(:w, webhook_secret), environment = :e, passthrough_tag = :t, updated_at = now(),
                  checked_at = case when cast(:i as text) is null and cast(:s as text) is null then checked_at end,
                  check_error = case when cast(:i as text) is null and cast(:s as text) is null then check_error end
                where org_id = :o""").params(p).update() == 0)
            db.sql("""
                    insert into media_connection(org_id, provider, token_id, token_secret, webhook_secret, environment, passthrough_tag)
                    values (:o, :p, :i, :s, :w, :e, :t)""").params(p).update();
    }

    /** Records a health check: error null = it worked. */
    public void checked(UUID org, String error) {
        db.sql("update media_connection set checked_at = now(), check_error = :e where org_id = :o").param("e", error).param("o", org).update();
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
