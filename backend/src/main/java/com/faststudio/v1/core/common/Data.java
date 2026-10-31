package com.faststudio.v1.core.common;

import com.faststudio.v1.services.rights.Rights;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared tenant-scoped reads. Every method takes orgId first: there is deliberately no
 * "find by id" that can return another tenant's row.
 */
@Component
public class Data {
    public final JdbcClient db;

    public Data(JdbcClient db) {
        this.db = db;
    }

    public record Asset(UUID id, UUID orgId, String title, String description, String kind, String genre, String language,
                        String series, Integer season, Integer episode, long durationMs, String cuePoints, String status,
                        String statusDetail, String mediaKey, String color, OffsetDateTime createdAt, OffsetDateTime updatedAt, String mediaType) {
        public List<Long> cues() {
            if (cuePoints == null || cuePoints.isBlank()) return List.of();
            return Arrays.stream(cuePoints.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::parseLong)
                    .filter(c -> c > 0 && c < durationMs).sorted().toList();
        }

        public boolean ready() { return "READY".equals(status); }
    }

    public record Channel(UUID id, UUID orgId, String slug, String name, String description, String genre, String language,
                          String timezone, String color, String territories, String status, int adSecondsPerHour,
                          int breakSeconds, UUID fallbackAssetId, UUID publishedVersionId, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public List<String> territoryList() { return Times.codes(territories); }
    }

    public record Version(UUID id, UUID channelId, int number, String status, int revision, String note, UUID createdBy,
                          OffsetDateTime createdAt, OffsetDateTime publishedAt) {}

    public record Entry(UUID id, UUID versionId, String kind, UUID assetId, long assetOffsetMs, OffsetDateTime startAt,
                        OffsetDateTime endAt, String ownership) {
        public Instant start() { return startAt.toInstant(); }
        public Instant end() { return endAt.toInstant(); }
        public long durationMs() { return end().toEpochMilli() - start().toEpochMilli(); }
        public boolean isBreak() { return "AD_BREAK".equals(kind); }
    }

    static final String ASSET_COLS = "id, org_id, title, description, kind, genre, language, series, season, episode, duration_ms, cue_points, status, status_detail, media_key, color, created_at, updated_at, coalesce(media_type,kind) as media_type";

    public Asset asset(UUID org, UUID id) {
        return db.sql("select " + ASSET_COLS + " from asset where org_id = :o and id = :id").param("o", org).param("id", id)
                .query(Asset.class).optional().orElseThrow(() -> ApiException.notFound("Video"));
    }

    public Map<UUID, Asset> assets(UUID org) {
        return db.sql("select " + ASSET_COLS + " from asset where org_id = :o").param("o", org).query(Asset.class).list()
                .stream().collect(Collectors.toMap(Asset::id, Function.identity()));
    }

    public Rights.Policy policy(UUID org, UUID assetId) {
        return db.sql("select id, asset_id, allowed_countries, blocked_countries, valid_from, valid_until, ads_allowed, notes from rights_policy where org_id = :o and asset_id = :a")
                .param("o", org).param("a", assetId).query(Rights.Policy.class).optional().orElse(null);
    }

    public Map<UUID, Rights.Policy> policies(UUID org) {
        return db.sql("select id, asset_id, allowed_countries, blocked_countries, valid_from, valid_until, ads_allowed, notes from rights_policy where org_id = :o")
                .param("o", org).query(Rights.Policy.class).list().stream().collect(Collectors.toMap(Rights.Policy::assetId, Function.identity()));
    }

    static final String CHANNEL_COLS = "id, org_id, slug, name, description, genre, language, timezone, color, territories, status, ad_seconds_per_hour, break_seconds, fallback_asset_id, published_version_id, created_at, updated_at";

    public Channel channel(UUID org, UUID id) {
        return db.sql("select " + CHANNEL_COLS + " from channel where org_id = :o and id = :id").param("o", org).param("id", id)
                .query(Channel.class).optional().orElseThrow(() -> ApiException.notFound("Channel"));
    }

    public List<Channel> channels(UUID org) {
        return db.sql("select " + CHANNEL_COLS + " from channel where org_id = :o and status <> 'ARCHIVED' order by created_at, name").param("o", org)
                .query(Channel.class).list();
    }

    static final String VERSION_COLS = "id, channel_id, number, status, revision, note, created_by, created_at, published_at";

    public Version draft(UUID org, UUID channelId) {
        return db.sql("select " + VERSION_COLS + " from schedule_version where org_id = :o and channel_id = :c and number = 0")
                .param("o", org).param("c", channelId).query(Version.class).single();
    }

    public List<Version> versions(UUID org, UUID channelId) {
        return db.sql("select " + VERSION_COLS + " from schedule_version where org_id = :o and channel_id = :c and number > 0 order by number desc")
                .param("o", org).param("c", channelId).query(Version.class).list();
    }

    static final String ENTRY_COLS = "id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership";

    public List<Entry> entries(UUID org, UUID versionId) {
        return db.sql("select " + ENTRY_COLS + " from schedule_entry where org_id = :o and version_id = :v order by start_at")
                .param("o", org).param("v", versionId).query(Entry.class).list();
    }

    public List<Entry> entries(UUID org, UUID versionId, Instant from, Instant to) {
        return db.sql("select " + ENTRY_COLS + " from schedule_entry where org_id = :o and version_id = :v and end_at > :f and start_at < :t order by start_at")
                .param("o", org).param("v", versionId).param("f", Times.utc(from)).param("t", Times.utc(to)).query(Entry.class).list();
    }

    /** Last instant covered by a version, or null when empty. */
    public Instant coverageEnd(UUID org, UUID versionId) {
        if (versionId == null) return null;
        OffsetDateTime t = db.sql("select max(end_at) from schedule_entry where org_id = :o and version_id = :v")
                .param("o", org).param("v", versionId).query(OffsetDateTime.class).list().stream().filter(Objects::nonNull).findFirst().orElse(null);
        return t == null ? null : t.toInstant();
    }
}
