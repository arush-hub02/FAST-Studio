package com.faststudio.v1.repositories.media;

import com.faststudio.v1.dto.media.AssetDto.ProcessingJob;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * SQL for media assets and their rights policies. Asset tables are protected by row-level security;
 * every query still filters by {@code org_id} so it also works under a platform/bypass scope.
 * Reading single assets/policies goes through {@code Data} (shared with scheduling).
 */
@Repository
public class AssetRepository {
    private final JdbcClient db;

    public AssetRepository(JdbcClient db) { this.db = db; }

    private record Usage(UUID assetId, String channel) {}

    // --- processing (called by the media job workers inside the job's project; MediaJobRepository writes the results) ---

    public ProcessingJob job(UUID org, UUID id) {
        return db.sql("""
                select a.id, a.org_id, o.slug, a.title, a.kind, a.color, a.generator, a.duration_ms, a.media_key, a.source_url, a.source_ref, a.connection_id
                from asset a join organization o on o.id = a.org_id where a.org_id = :o and a.id = :id""")
                .param("o", org).param("id", id).query(ProcessingJob.class).single();
    }

    /** Channels that air each asset in their published schedule from now on. */
    public Map<UUID, List<String>> upcomingAirings(UUID org) {
        Map<UUID, List<String>> out = new HashMap<>();
        db.sql("""
                select distinct e.asset_id, c.name as channel from schedule_entry e
                join channel c on c.org_id = e.org_id and c.published_version_id = e.version_id
                where e.org_id = :o and e.asset_id is not null and e.end_at > now() and e.kind = 'PROGRAM'""")
                .param("o", org).query(Usage.class).list()
                .forEach(u -> out.computeIfAbsent(u.assetId(), k -> new ArrayList<>()).add(u.channel()));
        return out;
    }

    public record NewAsset(String title, String description, String kind, String genre, String language, String series,
                           Integer season, Integer episode, String color, long durationMs, String generator, String status,
                           String cuePoints, String mediaType) {}

    public UUID insert(UUID org, NewAsset a) {
        return db.sql("""
                insert into asset(org_id, title, description, kind, genre, language, series, season, episode, color, duration_ms, generator, status, cue_points, media_type)
                values (:o, :title, :d, :k, :g, :l, :s, :se, :ep, :c, :dur, :gen, :st, :cues, :mt) returning id""")
                .param("o", org).param("title", a.title()).param("d", a.description()).param("k", a.kind()).param("g", a.genre())
                .param("l", a.language()).param("s", a.series()).param("se", a.season()).param("ep", a.episode()).param("c", a.color())
                .param("dur", a.durationMs()).param("gen", a.generator()).param("st", a.status()).param("cues", a.cuePoints())
                .param("mt", a.mediaType()).query(UUID.class).single();
    }

    /** A local upload replaces a library video as the source (ad creatives keep their ad-server identity). */
    public void markUploaded(UUID org, UUID id) {
        db.sql("""
                with s as (delete from asset_source where org_id = :o and asset_id = :id returning asset_id)
                update asset set status = 'PROCESSING', status_detail = 'Uploaded; converting to 5-second HLS segments', generator = null,
                source_ref = case when source_ref like 'mux%' or exists (select 1 from s) then null else source_ref end, updated_at = now()
                where org_id = :o and id = :id""")
                .param("o", org).param("id", id).update();
    }

    public void markProcessing(UUID org, UUID id, String detail) {
        db.sql("update asset set status = 'PROCESSING', status_detail = :d, updated_at = now() where org_id = :o and id = :id")
                .param("d", detail).param("o", org).param("id", id).update();
    }

    public record Metadata(String title, String description, String genre, String language, String cuePoints, String series,
                           Integer season, Integer episode, String mediaType, String kind) {}

    public void updateMetadata(UUID org, UUID id, Metadata m) {
        db.sql("""
                update asset set title = :t, description = :d, genre = :g, language = :l, cue_points = :c, series = :s, season = :se,
                episode = :ep, media_type = :mt, kind = :kind, updated_at = now() where org_id = :o and id = :id""")
                .param("t", m.title()).param("d", m.description()).param("g", m.genre()).param("l", m.language()).param("c", m.cuePoints())
                .param("s", m.series()).param("se", m.season()).param("ep", m.episode()).param("mt", m.mediaType()).param("kind", m.kind())
                .param("o", org).param("id", id).update();
    }

    /** Serializes episode-number checks of one project. */
    /** What a project stores and is converting right now (the per-project limits are checked against it). */
    public record ProjectUsage(long storedMs, int processing) {}

    public ProjectUsage usage(UUID org) {
        return db.sql("""
                select coalesce(sum(duration_ms) filter (where status <> 'ARCHIVED'), 0) as stored_ms,
                       count(*) filter (where status = 'PROCESSING') as processing
                from asset where org_id = :o""").param("o", org).query(ProjectUsage.class).single();
    }

    public void lockProject(UUID org) {
        db.sql("select id from organization where id = :o for update").param("o", org).query(UUID.class).single();
    }

    /** Whether another non-archived episode already has this series/season/episode (series compared case-insensitively). */
    public boolean episodeTaken(UUID org, String series, int season, int episode, UUID except) {
        return db.sql("""
                select count(*) from asset where org_id = :o and kind = 'EPISODE' and status <> 'ARCHIVED'
                and lower(btrim(series)) = lower(:s) and season = :se and episode = :ep and (cast(:id as uuid) is null or id <> :id)""")
                .param("o", org).param("s", series).param("se", season).param("ep", episode).param("id", except).query(Integer.class).single() > 0;
    }

    /** Channels whose draft schedule still uses the asset in the future. */
    public List<String> draftChannelsUsing(UUID org, UUID asset) {
        return db.sql("""
                select c.name from schedule_entry e join schedule_version v on v.id = e.version_id join channel c on c.id = v.channel_id
                where e.org_id = :o and e.asset_id = :a and v.number = 0 and e.end_at > now()""")
                .param("o", org).param("a", asset).query(String.class).list().stream().distinct().toList();
    }

    public List<String> channelsWithFallback(UUID org, UUID asset) {
        return db.sql("select name from channel where org_id = :o and fallback_asset_id = :a").param("o", org).param("a", asset).query(String.class).list();
    }

    public List<String> activeCampaignsUsing(UUID org, UUID asset) {
        return db.sql("select name from campaign where org_id = :o and creative_asset_id = :a and status = 'ACTIVE'")
                .param("o", org).param("a", asset).query(String.class).list();
    }

    public void archive(UUID org, UUID id) {
        db.sql("update asset set status = 'ARCHIVED', updated_at = now() where org_id = :o and id = :id").param("o", org).param("id", id).update();
    }

    public void upsertRights(UUID org, UUID asset, String allowed, String blocked, OffsetDateTime validFrom, OffsetDateTime validUntil,
                             boolean adsAllowed, String notes) {
        db.sql("""
                insert into rights_policy(org_id, asset_id, allowed_countries, blocked_countries, valid_from, valid_until, ads_allowed, notes)
                values (:o, :a, :al, :bl, :f, :u, :ads, :n)
                on conflict (org_id, asset_id) do update set allowed_countries = excluded.allowed_countries, blocked_countries = excluded.blocked_countries,
                  valid_from = excluded.valid_from, valid_until = excluded.valid_until, ads_allowed = excluded.ads_allowed, notes = excluded.notes, updated_at = now()""")
                .param("o", org).param("a", asset).param("al", allowed).param("bl", blocked).param("f", validFrom).param("u", validUntil)
                .param("ads", adsAllowed).param("n", notes).update();
    }

    // ------------------------------------------------------------------ video library (Mux) imports

    /** Marks library-linked assets failed when the provider could not process the file. */
    public void markLibraryFailed(UUID org, String ref, String uploadRef, String detail) {
        db.sql("""
                update asset set status = 'FAILED', status_detail = :d, updated_at = now()
                where org_id = :o and source_ref in (:r, :u) and status not in ('READY', 'ARCHIVED')""")
                .param("d", detail).param("o", org).param("r", ref).param("u", uploadRef).update();
    }

    /** Links a finished direct upload to the asset row created when its upload URL was minted. */
    public Optional<UUID> linkUploadedAsset(UUID org, String uploadRef, String ref, String detail) {
        return db.sql("""
                update asset set source_ref = :r, status = 'PROCESSING', status_detail = :d, updated_at = now()
                where org_id = :o and source_ref = :u returning id""")
                .param("r", ref).param("d", detail).param("o", org).param("u", uploadRef).query(UUID.class).optional();
    }

    /** New MOVIE row for a library video; idempotent on (org, source_ref). Empty when it already exists. */
    public Optional<UUID> insertLibraryAsset(UUID org, String title, long durationMs, String detail, String cuePoints, String ref, UUID connection) {
        return db.sql("""
                insert into asset(org_id, title, kind, duration_ms, status, status_detail, cue_points, source_ref, connection_id)
                values (:o, :t, 'MOVIE', :ms, 'DRAFT', :d, :c, :r, :conn)
                on conflict (org_id, source_ref) where source_ref is not null do nothing returning id""")
                .param("o", org).param("t", title).param("ms", durationMs).param("d", detail).param("c", cuePoints).param("r", ref)
                .param("conn", connection).query(UUID.class).optional();
    }

    /** The asset now waits for its direct upload to the library connection (uploadRef "{provider}-upload:{upload id}"). */
    public void setUploadReference(UUID org, UUID id, String uploadRef, UUID connection) {
        db.sql("""
                update asset set source_ref = :r, connection_id = :c, status = 'DRAFT', status_detail = 'Uploading to the video library',
                  updated_at = now() where org_id = :o and id = :id""")
                .param("r", uploadRef).param("c", connection).param("o", org).param("id", id).update();
    }

    /** A new project slate waiting for its file (it never appears in the Library or in schedules). */
    public UUID insertSlate(UUID org, boolean nextSchedule) {
        return db.sql("""
                insert into asset(org_id, title, kind, status, status_detail, genre, color, slate_next_schedule)
                values (:o, 'Slate', 'SLATE', 'DRAFT', 'Waiting for the file', 'Slate', '#1e1b4b', :deferred) returning id""")
                .param("o", org).param("deferred", nextSchedule).query(UUID.class).single();
    }

    public record Slate(UUID id, boolean nextSchedule) {}

    /** The project's slates that aren't archived, newest first. */
    public List<Slate> slates(UUID org) {
        return db.sql("""
                select id, slate_next_schedule as next_schedule from asset
                where org_id = :o and kind = 'SLATE' and status <> 'ARCHIVED' order by created_at desc, id""")
                .param("o", org).query(Slate.class).list();
    }

    /** A deferred slate is eligible only after this channel publishes a schedule after the upload. */
    public List<String> slateKeys(UUID org, UUID publishedVersion) {
        return db.sql("""
                select media_key from asset
                where org_id = :o and kind = 'SLATE' and status = 'READY' and media_key is not null
                  and (not slate_next_schedule or created_at <= (
                    select published_at from schedule_version where org_id = :o and id = :v))
                order by created_at desc, id""").param("o", org).param("v", publishedVersion).query(String.class).list();
    }

    /** A direct upload not yet linked to its library video. */
    public record PendingUpload(UUID orgId, UUID id, String sourceRef, UUID connectionId) {}

    /** Direct uploads of the last day still waiting for their library video, every project (the upload watcher). */
    public List<PendingUpload> pendingUploads() {
        return db.sql("""
                select org_id, id, source_ref, connection_id from asset
                where source_ref like '%-upload:%' and connection_id is not null and status not in ('ARCHIVED','FAILED')
                  and updated_at > now() - interval '1 day' /* all tenants: upload watcher */""").query(PendingUpload.class).list();
    }

    /** Raw comma-separated feature switches of a project (filtered by plan in the tenancy service). */
    public String projectFeatures(UUID org) {
        return db.sql("select features from organization where id = :o").param("o", org).query(String.class).single();
    }
}
