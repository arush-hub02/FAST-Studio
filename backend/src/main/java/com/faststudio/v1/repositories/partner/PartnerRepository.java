package com.faststudio.v1.repositories.partner;

import com.faststudio.v1.dto.partner.PartnerDto.Day;
import com.faststudio.v1.dto.partner.PartnerDto.Destination;
import com.faststudio.v1.dto.partner.PartnerDto.Named;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * SQL for the partner side: sharings, the shared owner data, delivery counts and API clients.
 * Queries on the owner's data run while the service has switched row-level security to that owner.
 */
@Repository
public class PartnerRepository {
    private final JdbcClient db;

    public PartnerRepository(JdbcClient db) { this.db = db; }

    // ------------------------------------------------------------------ sharings

    public record LinkRow(UUID id, UUID ownerId, String ownerSlug, String ownerName, String status, String scopes, boolean allChannels,
                          UUID destinationId, String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt, OffsetDateTime expiresAt) {}

    /** Sharings to this partner that are invited or active. */
    public List<LinkRow> links(UUID partner) {
        return db.sql("""
                select l.id, o.id as owner_id, o.slug as owner_slug, o.name as owner_name, l.status, l.scopes, l.all_channels, l.destination_id,
                  l.note, l.created_at, l.accepted_at, l.expires_at
                from partner_link l join organization o on o.id = l.owner_org_id
                where l.partner_org_id = :p and l.status <> 'REVOKED' order by l.status, o.name""")
                .param("p", partner).query(LinkRow.class).list();
    }

    /** Accepts an invited sharing atomically; returns the owner, or empty when nothing was waiting. */
    public Optional<UUID> accept(UUID partner, UUID link, UUID by) {
        return db.sql("""
                update partner_link set status = 'ACTIVE', accepted_by = :u, accepted_at = now()
                where id = :l and partner_org_id = :p and status = 'INVITED' returning owner_org_id""")
                .param("u", by).param("l", link).param("p", partner).query(UUID.class).optional();
    }

    public List<Named> ownerActiveChannels(UUID owner) {
        return db.sql("select id, name from channel where org_id = :o and status <> 'ARCHIVED' order by name").param("o", owner).query(Named.class).list();
    }

    public List<Named> linkChannels(UUID link) {
        return db.sql("""
                select c.id, c.name from partner_link_channel x join channel c on c.org_id = x.org_id and c.id = x.channel_id
                where x.link_id = :l order by c.name""").param("l", link).query(Named.class).list();
    }

    public Set<UUID> linkChannelIds(UUID link) {
        return new HashSet<>(db.sql("select channel_id from partner_link_channel where link_id = :l").param("l", link).query(UUID.class).list());
    }

    public Optional<Destination> destination(UUID owner, UUID destination) {
        return db.sql("select name, ad_mode, marker_format, max_ad_seconds_per_hour from destination where org_id = :o and id = :d")
                .param("o", owner).param("d", destination).query(Destination.class).optional();
    }

    // ------------------------------------------------------------------ delivery counts (partner's own destination only)

    private Map<String, Object> scope(UUID owner, UUID channel, UUID destination, int days) {
        return Map.of("o", owner, "c", channel, "d", destination, "n", days);
    }

    public long sessions(UUID owner, UUID channel, UUID destination, int days) {
        return db.sql("select count(*) from playback_session where org_id = :o and channel_id = :c and destination_id = :d and created_at > now() - make_interval(days => :n)")
                .params(scope(owner, channel, destination, days)).query(Long.class).single();
    }

    /** IMPRESSION or COMPLETE events from sessions on the destination. */
    public long adEvents(UUID owner, UUID channel, UUID destination, int days, String event) {
        Map<String, Object> p = new HashMap<>(scope(owner, channel, destination, days));
        p.put("event", event);
        return db.sql("""
                select count(*) from ad_event e join playback_session s on s.org_id = e.org_id and s.id = e.session_id
                where e.org_id = :o and e.channel_id = :c and s.destination_id = :d and e.event = :event and e.occurred_at > now() - make_interval(days => :n)""")
                .params(p).query(Long.class).single();
    }

    public List<Day> byDay(UUID owner, UUID channel, UUID destination, int days) {
        return db.sql("""
                select d::date as day,
                  (select count(*) from playback_session s where s.org_id = :o and s.channel_id = :c and s.destination_id = :d and s.created_at::date = d::date) as viewer_sessions,
                  (select count(*) from ad_event e join playback_session s on s.org_id = e.org_id and s.id = e.session_id
                     where e.org_id = :o and e.channel_id = :c and s.destination_id = :d and e.event = 'IMPRESSION' and e.occurred_at::date = d::date) as ad_impressions
                from generate_series(current_date - (:n - 1), current_date, interval '1 day') d order by d""")
                .params(scope(owner, channel, destination, days)).query(Day.class).list();
    }

    // ------------------------------------------------------------------ API clients

    public record ClientRow(UUID id, String name, String scopes, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {}

    public List<ClientRow> clients(UUID partner) {
        return db.sql("select id, name, scopes, created_at, last_used_at, revoked_at from api_client where org_id = :o order by revoked_at nulls first, created_at desc")
                .param("o", partner).query(ClientRow.class).list();
    }

    public UUID insertClient(UUID partner, String name, String secretHash, String scopes, UUID createdBy) {
        return db.sql("insert into api_client(org_id, name, secret_hash, scopes, created_by) values (:o, :n, :h, :s, :u) returning id")
                .param("o", partner).param("n", name).param("h", secretHash).param("s", scopes).param("u", createdBy).query(UUID.class).single();
    }

    public boolean revokeClient(UUID partner, UUID client) {
        return db.sql("update api_client set revoked_at = now() where id = :id and org_id = :o and revoked_at is null")
                .param("id", client).param("o", partner).update() > 0;
    }

    public record ClientSecret(UUID id, UUID orgId, String name, String secretHash, String scopes, String orgStatus) {}

    /** An unrevoked client of a partner organization, with its organization's status. */
    public Optional<ClientSecret> activeClient(UUID client) {
        return db.sql("""
                select c.id, c.org_id, c.name, c.secret_hash, c.scopes, o.status as org_status from api_client c join organization o on o.id = c.org_id
                where c.id = :id and c.revoked_at is null and o.kind = 'PARTNER'""").param("id", client).query(ClientSecret.class).optional();
    }

    public void touchClient(UUID client) {
        db.sql("update api_client set last_used_at = now() where id = :id").param("id", client).update();
    }
}
