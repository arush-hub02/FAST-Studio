package com.faststudio.v1.repositories.ads;

import com.faststudio.v1.dto.ads.AdsDto.Creative;
import com.faststudio.v1.services.ads.sources.IAdSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL for the ad waterfall (ad_source), stored per-viewer break decisions (ad_decision) and ad creatives fetched from ad servers. */
@Repository
public class AdSourceRepository {
    private final JdbcClient db;

    public AdSourceRepository(JdbcClient db) { this.db = db; }

    // --- break decisions ---

    /** Transaction-scoped lock so concurrent sessions of one viewer reserve ads one at a time (also across server instances). */
    public void lockDecisions(String key) {
        db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))").param("key", key).query((rs, n) -> true).single();
    }

    /** The decision JSON already stored for this session and break, if any. */
    public Optional<String> decision(UUID org, UUID session, UUID breakId) {
        return db.sql("select decision from ad_decision where org_id=:o and session_id=:s and break_id=:b")
                .param("o", org).param("s", session).param("b", breakId).query(String.class).optional();
    }

    public void saveDecision(UUID org, UUID session, UUID breakId, String decisionJson) {
        db.sql("insert into ad_decision(org_id,session_id,break_id,decision) values (:o,:s,:b,:d)")
                .param("o", org).param("s", session).param("b", breakId).param("d", decisionJson).update();
    }

    /** The slug of the channel whose schedule holds this break, or "" when unknown. */
    public String channelSlugOfBreak(UUID org, UUID breakId) {
        return db.sql("""
                select c.slug from schedule_entry e join schedule_version v on v.org_id = e.org_id and v.id = e.version_id
                join channel c on c.org_id = v.org_id and c.id = v.channel_id where e.org_id = :o and e.id = :b""")
                .param("o", org).param("b", breakId).query(String.class).optional().orElse("");
    }

    // --- waterfall ---

    /** Configured sources in waterfall order; empty when the project never edited its waterfall. */
    public List<IAdSource.Config> waterfall(UUID org) {
        return db.sql("select id, name, kind, tag_url, timeout_ms, position, enabled, managed_by from ad_source where org_id = :o order by position, created_at")
                .param("o", org).query(IAdSource.Config.class).list();
    }

    public int count(UUID org) {
        return db.sql("select count(*) from ad_source where org_id = :o").param("o", org).query(Integer.class).single();
    }

    public boolean hasDirect(UUID org) {
        return db.sql("select count(*) from ad_source where org_id = :o and kind = 'DIRECT'").param("o", org).query(Integer.class).single() > 0;
    }

    public void insertDirect(UUID org, String name) {
        db.sql("insert into ad_source(org_id, name, kind, timeout_ms, position) values (:o, :n, 'DIRECT', 1000, 0)").param("o", org).param("n", name).update();
    }

    /** Appends a source at the end of the waterfall. */
    public UUID insert(UUID org, String name, String kind, String tagUrl, int timeoutMs, boolean enabled, String managedBy) {
        return db.sql("""
                insert into ad_source(org_id, name, kind, tag_url, timeout_ms, position, enabled, managed_by)
                values (:o, :n, :k, :u, :ms, (select coalesce(max(position), -1) + 1 from ad_source where org_id = :o), :en, :by) returning id""")
                .param("o", org).param("n", name).param("k", kind).param("u", tagUrl).param("ms", timeoutMs)
                .param("en", enabled).param("by", managedBy).query(UUID.class).single();
    }

    /** Stored VAST tag URL; onlyManagedBy (null = any) limits which rows the caller may read. */
    public Optional<String> vastTagUrl(UUID org, UUID id, String onlyManagedBy) {
        return db.sql("select tag_url from ad_source where org_id=:o and id=:id and kind='VAST'" + (onlyManagedBy == null ? "" : " and managed_by=:by"))
                .param("o", org).param("id", id).params(by(onlyManagedBy)).query(String.class).optional();
    }

    /** False when no row of that kind (and owner) exists. */
    public boolean update(UUID org, UUID id, String kind, String name, String tagUrl, int timeoutMs, boolean enabled, String onlyManagedBy) {
        return db.sql("update ad_source set name = :n, tag_url = :u, timeout_ms = :ms, enabled = :en where org_id = :o and id = :id and kind = :k"
                        + (onlyManagedBy == null ? "" : " and managed_by = :by"))
                .param("n", name).param("u", tagUrl).param("ms", timeoutMs).param("en", enabled)
                .param("o", org).param("id", id).param("k", kind).params(by(onlyManagedBy)).update() > 0;
    }

    public boolean delete(UUID org, UUID id, String onlyManagedBy) {
        return db.sql("delete from ad_source where org_id = :o and id = :id" + (onlyManagedBy == null ? "" : " and managed_by = :by"))
                .param("o", org).param("id", id).params(by(onlyManagedBy)).update() > 0;
    }

    public void setPosition(UUID org, UUID id, int position) {
        db.sql("update ad_source set position = :p where org_id = :o and id = :id").param("p", position).param("o", org).param("id", id).update();
    }

    /** A stored tag URL (sealed, or plaintext from before encryption) for the start-up re-encryption. */
    public record StoredTag(UUID orgId, UUID id, String tagUrl) {}

    /** Tags still stored as plaintext (they contain "://"; sealed values are Base64). Run under TenantScope.bypass. */
    public List<StoredTag> plaintextTags() {
        return db.sql("select org_id, id, tag_url from ad_source where tag_url like '%://%'").query(StoredTag.class).list();
    }

    public void setTagUrl(UUID org, UUID id, String tagUrl) {
        db.sql("update ad_source set tag_url = :u where org_id = :o and id = :id").param("u", tagUrl).param("o", org).param("id", id).update();
    }

    private static Map<String, Object> by(String managedBy) {
        return managedBy == null ? Map.of() : Map.of("by", managedBy);
    }

    // --- creatives from external ad servers (stored as AD assets keyed by source_ref) ---

    public Optional<Creative> creative(UUID org, String ref) {
        return db.sql("select id, status, media_key, duration_ms from asset where org_id = :o and source_ref = :r")
                .param("o", org).param("r", ref).query(Creative.class).optional();
    }

    /** New AD asset waiting for conditioning; empty when another request inserted the same creative first. */
    public Optional<UUID> insertCreative(UUID org, String title, String description, long durationMs, String mediaUrl, String ref) {
        return db.sql("""
                insert into asset(org_id, title, description, kind, genre, duration_ms, status, status_detail, color, source_url, source_ref)
                values (:o, :t, :d, 'AD', 'Advertising', :ms, 'PROCESSING', 'Conditioning ad creative into 5-second segments', '#64748b', :u, :r)
                on conflict (org_id, source_ref) where source_ref is not null do nothing returning id""")
                .param("o", org).param("t", title).param("d", description).param("ms", durationMs).param("u", mediaUrl).param("r", ref)
                .query(UUID.class).optional();
    }
}
