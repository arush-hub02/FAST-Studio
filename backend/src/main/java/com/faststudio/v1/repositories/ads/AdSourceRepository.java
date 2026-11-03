package com.faststudio.v1.repositories.ads;

import com.faststudio.v1.dto.ads.AdsDto.Creative;
import com.faststudio.v1.services.ads.sources.IAdSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL for the ad waterfall (instances of the ad connectors), stored per-viewer break decisions (ad_decision) and ad creatives fetched from ad servers. */
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

    // --- waterfall: instances of the ad connectors (driver DIRECT or VAST), in position order ---

    private static final String AD_INSTANCE = " connector_instance i join connector_definition d on d.key = i.connector_key and d.driver in ('DIRECT','VAST') ";

    /** Configured sources in waterfall order; empty when the project never edited its waterfall. Timeout kept within 100–5000 ms. */
    public List<IAdSource.Config> waterfall(UUID org) {
        return db.sql("""
                select i.id, i.name, d.driver as kind, t.value as tag_url, i.position, i.enabled, i.managed_by,
                  least(5000, greatest(100, coalesce(substring(m.value from '^[0-9]{1,6}$'), substring(p.default_value from '^[0-9]{1,6}$'), '1000')::int)) as timeout_ms
                from """ + AD_INSTANCE + """

                left join connector_value t on t.instance_id = i.id and t.property_key = 'tag_url'
                left join connector_value m on m.instance_id = i.id and m.property_key = 'timeout_ms'
                left join connector_property p on p.connector_key = i.connector_key and p.key = 'timeout_ms'
                where i.org_id = :o order by i.position, i.created_at""")
                .param("o", org).query(IAdSource.Config.class).list();
    }

    public int count(UUID org) {
        return db.sql("select count(*) from " + AD_INSTANCE + " where i.org_id = :o").param("o", org).query(Integer.class).single();
    }

    public boolean hasDirect(UUID org) {
        return db.sql("select count(*) from " + AD_INSTANCE + " where i.org_id = :o and d.driver = 'DIRECT'").param("o", org).query(Integer.class).single() > 0;
    }

    public void insertDirect(UUID org, String name) {
        db.sql("insert into connector_instance(connector_key, org_id, name, position) values ('direct', :o, :n, :p)")
                .param("o", org).param("n", name).param("p", nextPosition(org)).update();
    }

    /** Next waterfall position: shared by every multi-instance connector of the project (ad servers). */
    public int nextPosition(UUID org) {
        return db.sql("""
                select coalesce(max(i.position), 0) + 1 from connector_instance i join connector_definition d on d.key = i.connector_key
                where d.multi_instance and i.org_id = :o""").param("o", org).query(Integer.class).single();
    }

    /** Appends a source at the end of the waterfall; tagUrl is already sealed. */
    public UUID insert(UUID org, String name, String kind, String tagUrl, int timeoutMs, boolean enabled, String managedBy) {
        UUID id = db.sql("""
                insert into connector_instance(connector_key, org_id, name, position, enabled, managed_by)
                values (:k, :o, :n, :p, :en, :by) returning id""")
                .param("k", key(kind)).param("o", org).param("n", name).param("p", nextPosition(org)).param("en", enabled).param("by", managedBy)
                .query(UUID.class).single();
        if ("VAST".equals(kind)) putVast(org, id, tagUrl, timeoutMs);
        return id;
    }

    /** Stored (sealed) VAST tag URL; onlyManagedBy (null = any) limits which rows the caller may read. */
    public Optional<String> vastTagUrl(UUID org, UUID id, String onlyManagedBy) {
        return db.sql("""
                select coalesce(v.value, '') from connector_instance i join connector_definition d on d.key = i.connector_key and d.driver = 'VAST'
                left join connector_value v on v.instance_id = i.id and v.property_key = 'tag_url'
                where i.org_id = :o and i.id = :id""" + (onlyManagedBy == null ? "" : " and i.managed_by = :by"))
                .param("o", org).param("id", id).params(by(onlyManagedBy)).query(String.class).optional().filter(s -> !s.isEmpty());
    }

    /** False when no row of that kind (and owner) exists. */
    public boolean update(UUID org, UUID id, String kind, String name, String tagUrl, int timeoutMs, boolean enabled, String onlyManagedBy) {
        boolean found = db.sql("""
                update connector_instance i set name = :n, enabled = :en, updated_at = now() from connector_definition d
                where d.key = i.connector_key and d.driver = :k and i.org_id = :o and i.id = :id""" + (onlyManagedBy == null ? "" : " and i.managed_by = :by"))
                .param("n", name).param("en", enabled).param("k", kind).param("o", org).param("id", id).params(by(onlyManagedBy)).update() > 0;
        if (found && "VAST".equals(kind)) putVast(org, id, tagUrl, timeoutMs);
        return found;
    }

    private void putVast(UUID org, UUID id, String tagUrl, int timeoutMs) {
        put(org, id, "tag_url", tagUrl);
        put(org, id, "timeout_ms", Integer.toString(timeoutMs));
    }

    private void put(UUID org, UUID id, String key, String value) {
        db.sql("""
                insert into connector_value(instance_id, org_id, property_key, value) values (:i, :o, :k, :v)
                on conflict (instance_id, property_key) do update set value = excluded.value, updated_at = now()""")
                .param("i", id).param("o", org).param("k", key).param("v", value).update();
    }

    public boolean delete(UUID org, UUID id, String onlyManagedBy) {
        return db.sql("delete from connector_instance i using connector_definition d where d.key = i.connector_key and d.driver in ('DIRECT','VAST') and i.org_id = :o and i.id = :id"
                        + (onlyManagedBy == null ? "" : " and i.managed_by = :by"))
                .param("o", org).param("id", id).params(by(onlyManagedBy)).update() > 0;
    }

    /** position is the 0-based place in the waterfall; instances use 1..n. */
    public void setPosition(UUID org, UUID id, int position) {
        db.sql("update connector_instance set position = :p, updated_at = now() where org_id = :o and id = :id").param("p", position + 1).param("o", org).param("id", id).update();
    }

    /** A stored tag URL (sealed, or plaintext from before encryption) for the start-up re-encryption. */
    public record StoredTag(UUID orgId, UUID id, String tagUrl) {}

    /** Tags still stored as plaintext (they contain "://"; sealed values are Base64). Run under TenantScope.bypass. */
    public List<StoredTag> plaintextTags() {
        return db.sql("select org_id, instance_id as id, value as tag_url from connector_value where property_key = 'tag_url' and value like '%://%'")
                .query(StoredTag.class).list();
    }

    public void setTagUrl(UUID org, UUID id, String tagUrl) { put(org, id, "tag_url", tagUrl); }

    private static String key(String kind) { return "DIRECT".equals(kind) ? "direct" : "vast"; }

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
