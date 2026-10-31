package com.faststudio.v1.repositories.media;

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
                update asset set status = 'PROCESSING', status_detail = 'Uploaded; converting to 5-second HLS segments', generator = null,
                source_ref = case when source_ref like 'mux%' then null else source_ref end, updated_at = now() where org_id = :o and id = :id""")
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
    public Optional<UUID> insertLibraryAsset(UUID org, String title, long durationMs, String detail, String cuePoints, String ref) {
        return db.sql("""
                insert into asset(org_id, title, kind, duration_ms, status, status_detail, cue_points, source_ref)
                values (:o, :t, 'MOVIE', :ms, 'PROCESSING', :d, :c, :r)
                on conflict (org_id, source_ref) where source_ref is not null do nothing returning id""")
                .param("o", org).param("t", title).param("ms", durationMs).param("d", detail).param("c", cuePoints).param("r", ref)
                .query(UUID.class).optional();
    }

    public void setUploadReference(UUID org, UUID id, String uploadRef) {
        db.sql("update asset set source_ref = :r, status_detail = 'Waiting for the upload to finish', updated_at = now() where org_id = :o and id = :id")
                .param("r", uploadRef).param("o", org).param("id", id).update();
    }

    /** Raw comma-separated feature switches of a project (filtered by plan in the tenancy service). */
    public String projectFeatures(UUID org) {
        return db.sql("select features from organization where id = :o").param("o", org).query(String.class).single();
    }
}
