package com.faststudio.v1.repositories.media;

import com.faststudio.v1.dto.media.AssetDto.JobView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for durable media jobs (V22, docs/MEDIA_JOBS_DESIGN.md). Every state change is one statement, so a job and its
 * asset never disagree: publishing, failing and cancelling update both rows together, and only while the worker's
 * fence still matches. Claims, heartbeats and the reaper are system work across tenants; the rest names the project.
 */
@Repository
public class MediaJobRepository {
    private static final String ACTIVE = "('QUEUED','RUNNING','CANCEL_REQUESTED')";
    // a cancelled job leaves the asset as it was: its old media keeps airing, or it was never ready
    private static final String REVERT = "status = case when a.media_key is null then 'DRAFT' else 'READY' end, status_detail = 'Processing cancelled', updated_at = now()";

    private final JdbcClient db;

    public MediaJobRepository(JdbcClient db) { this.db = db; }

    /** A claimed job. kind PACKAGE (one asset) or SYNC (the project's library; assetId null). */
    public record Claim(UUID id, UUID orgId, UUID assetId, String kind, String lane, long fence, int attempts) {}

    /** Queues packaging unless the asset already has an active job; true when a job was added. */
    public boolean enqueue(UUID org, UUID asset, String lane) {
        return db.sql("insert into media_job(org_id, asset_id, lane) values (:o, :a, :l) on conflict (asset_id, kind) where state in " + ACTIVE + " do nothing")
                .param("o", org).param("a", asset).param("l", lane).update() > 0;
    }

    /** Takes the next due job of the lane, skipping projects already running their share of programme jobs. */
    public Optional<Claim> claim(String lane, String worker, int leaseSeconds, int perProject) {
        // ponytail: two servers claiming at the same instant can put one project one job over its share; take an advisory lock per lane if that matters
        return db.sql("""
                update media_job set state = 'RUNNING', lease_owner = :w, lease_until = now() + make_interval(secs => :lease),
                       attempts = attempts + 1, fence = fence + 1, started_at = now(), last_error = null
                where id = (select id from media_job
                            where state = 'QUEUED' and lane = :l and next_run_at <= now()
                              and (:l = 'AD' or org_id not in (select org_id from media_job where lane = 'PROGRAMME' and state in ('RUNNING','CANCEL_REQUESTED')
                                                               group by org_id having count(*) >= :max))
                            order by next_run_at, created_at for update skip locked limit 1)
                returning id, org_id, asset_id, kind, lane, fence, attempts /* all tenants: worker claim */""")
                .param("w", worker).param("lease", leaseSeconds).param("l", lane).param("max", perProject).query(Claim.class).optional();
    }

    /** Extends the lease; the job's state, or empty when this worker no longer holds it. */
    public Optional<String> heartbeat(Claim c, int leaseSeconds) {
        return db.sql("""
                update media_job set lease_until = now() + make_interval(secs => :lease)
                where id = :id and org_id = :o and fence = :f and state in ('RUNNING','CANCEL_REQUESTED') returning state""")
                .param("lease", leaseSeconds).param("id", c.id()).param("o", c.orgId()).param("f", c.fence()).query(String.class).optional();
    }

    /** Publishes the new media revision and completes the job; false when the fence no longer matches (nothing changed). */
    public boolean publish(Claim c, String detail, long durationMs, String mediaKey) {
        return db.sql("""
                with j as (update media_job set state = 'SUCCEEDED', finished_at = now(), lease_owner = null, lease_until = null
                           where id = :id and org_id = :o and fence = :f and state = 'RUNNING' returning org_id, asset_id)
                update asset a set status = 'READY', status_detail = :d, duration_ms = :ms, media_key = :k, updated_at = now()
                from j where a.org_id = j.org_id and a.id = j.asset_id""")
                .param("id", c.id()).param("o", c.orgId()).param("f", c.fence()).param("d", detail).param("ms", durationMs).param("k", mediaKey)
                .update() > 0;
    }

    /**
     * Records a failure. A retryable one goes back to the queue with backoff (1, 5, 25, 125 minutes) until its last
     * attempt; then, or for a permanent one, job and asset fail. The new job state, or empty when the fence no longer matches.
     */
    public Optional<String> fail(Claim c, String error, boolean retry) {
        return db.sql("""
                with j as (update media_job set state = case when :r and attempts < max_attempts then 'QUEUED' else 'FAILED' end,
                                  next_run_at = now() + interval '1 minute' * power(5, attempts - 1),
                                  finished_at = case when :r and attempts < max_attempts then null else now() end,
                                  lease_owner = null, lease_until = null, last_error = :e
                           where id = :id and org_id = :o and fence = :f and state = 'RUNNING' returning org_id, asset_id, state),
                     x as (update asset a set status = case when j.state = 'FAILED' then 'FAILED' else a.status end,
                                  status_detail = case when j.state = 'FAILED' then :e else 'Will retry shortly: ' || :e end, updated_at = now()
                           from j where a.org_id = j.org_id and a.id = j.asset_id)
                select state from j""")
                .param("r", retry).param("e", error).param("id", c.id()).param("o", c.orgId()).param("f", c.fence()).query(String.class).optional();
    }

    /** The worker acknowledges a cancel request it saw on a heartbeat; the asset goes back to how it was. */
    public void cancelled(Claim c) {
        db.sql("""
                with j as (update media_job set state = 'CANCELLED', finished_at = now(), lease_owner = null, lease_until = null
                           where id = :id and org_id = :o and fence = :f and state = 'CANCEL_REQUESTED' returning org_id, asset_id)
                update asset a set %s from j where a.org_id = j.org_id and a.id = j.asset_id and a.status = 'PROCESSING'""".formatted(REVERT))
                .param("id", c.id()).param("o", c.orgId()).param("f", c.fence()).update();
    }

    /** Cancels the asset's active job: a queued one at once, a running one at its worker's next heartbeat. The new state, if any. */
    public Optional<String> cancel(UUID org, UUID asset) {
        return db.sql("""
                with j as (update media_job set state = case when state = 'QUEUED' then 'CANCELLED' else 'CANCEL_REQUESTED' end,
                                  finished_at = case when state = 'QUEUED' then now() end
                           where org_id = :o and asset_id = :a and state in ('QUEUED','RUNNING') returning org_id, asset_id, state),
                     x as (update asset a set %s from j where a.org_id = j.org_id and a.id = j.asset_id and j.state = 'CANCELLED' and a.status = 'PROCESSING')
                select state from j""".formatted(REVERT))
                .param("o", org).param("a", asset).query(String.class).optional();
    }

    /** Jobs whose worker stopped heartbeating: re-queued with backoff, or failed after the last attempt; cancels are completed. */
    public int reap() {
        String why = "The server working on this video stopped.";
        int n = db.sql("""
                with j as (update media_job set state = case when attempts < max_attempts then 'QUEUED' else 'FAILED' end,
                                  next_run_at = now() + interval '1 minute' * power(5, attempts - 1),
                                  finished_at = case when attempts < max_attempts then null else now() end,
                                  lease_owner = null, lease_until = null, last_error = :e
                           where state = 'RUNNING' and lease_until < now() returning org_id, asset_id, state),
                     x as (update asset a set status = 'FAILED', status_detail = :e, updated_at = now()
                           from j where a.org_id = j.org_id and a.id = j.asset_id and j.state = 'FAILED')
                select count(*) from j /* all tenants: lease reaper */""").param("e", why).query(Integer.class).single();
        return n + db.sql("""
                with j as (update media_job set state = 'CANCELLED', finished_at = now(), lease_owner = null, lease_until = null
                           where state = 'CANCEL_REQUESTED' and lease_until < now() returning org_id, asset_id)
                update asset a set %s from j where a.org_id = j.org_id and a.id = j.asset_id and a.status = 'PROCESSING' /* all tenants: lease reaper */"""
                .formatted(REVERT)).update();
    }

    /** Finished jobs older than 30 days. */
    public int purge() {
        return db.sql("delete from media_job where state in ('SUCCEEDED','FAILED','CANCELLED') and finished_at < now() - interval '30 days' /* all tenants: retention */")
                .update();
    }

    /** The asset's last 10 jobs, newest first. */
    public List<JobView> recent(UUID org, UUID asset) {
        return db.sql("""
                select id, state, lane, attempts, max_attempts, last_error, created_at, started_at, finished_at,
                       case when state = 'QUEUED' then next_run_at end as next_run_at
                from media_job where org_id = :o and asset_id = :a order by created_at desc limit 10""")
                .param("o", org).param("a", asset).query(JobView.class).list();
    }

    /** Queues a library sync unless one is already queued or running; true when it was added. */
    public boolean enqueueSync(UUID org) {
        return db.sql("insert into media_job(org_id, kind, lane) values (:o, 'SYNC', 'PROGRAMME') on conflict (org_id) where kind = 'SYNC' and state in "
                + ACTIVE + " do nothing").param("o", org).update() > 0;
    }

    /** The project's latest library sync job, if it ever had one. */
    public Optional<JobView> latestSync(UUID org) {
        return db.sql("""
                select id, state, lane, attempts, max_attempts, last_error, created_at, started_at, finished_at,
                       case when state = 'QUEUED' then next_run_at end as next_run_at
                from media_job where org_id = :o and kind = 'SYNC' order by created_at desc limit 1""")
                .param("o", org).query(JobView.class).optional();
    }
}
