package com.faststudio.v1.repositories.distribution;

import com.faststudio.v1.dto.distribution.DistributionDto.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for destinations, deliveries and partner sharings of a project (customer side). */
@Repository
public class DistributionRepository {
    private final JdbcClient db;

    public DistributionRepository(JdbcClient db) { this.db = db; }

    // ------------------------------------------------------------------ destinations and deliveries

    private static final String DESTINATION_COLUMNS = "id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour, notes, created_at";

    public List<Destination> destinations(UUID org) {
        return db.sql("select " + DESTINATION_COLUMNS + " from destination where org_id = :o order by kind, name").param("o", org).query(Destination.class).list();
    }

    public Optional<Destination> destination(UUID org, UUID id) {
        return db.sql("select " + DESTINATION_COLUMNS + " from destination where org_id = :o and id = :id").param("o", org).param("id", id).query(Destination.class).optional();
    }

    public UUID insertDestination(UUID org, DestinationRequest r) {
        return db.sql("insert into destination(org_id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour, notes) values (:o, :n, :k, :m, :f, :max, :notes) returning id")
                .param("o", org).param("n", r.name().trim()).param("k", r.kind()).param("m", r.adMode()).param("f", r.markerFormat())
                .param("max", r.maxAdSecondsPerHour()).param("notes", r.notes() == null ? "" : r.notes()).query(UUID.class).single();
    }

    /** The 50 most recent deliveries. */
    public List<Publication> recentPublications(UUID org) {
        return db.sql("""
                select p.id, p.channel_id, c.name as channel, p.destination_id, d.name as destination, p.version_number, p.status, p.message, p.simulated, p.created_at
                from publication p join channel c on c.id = p.channel_id join destination d on d.id = p.destination_id
                where p.org_id = :o order by p.created_at desc limit 50""").param("o", org).query(Publication.class).list();
    }

    public UUID insertPublication(UUID org, UUID channel, UUID destination, Integer version, boolean ok, String message, boolean simulated) {
        return db.sql("insert into publication(org_id, channel_id, destination_id, version_number, status, message, simulated) values (:o, :c, :d, :v, :s, :m, :sim) returning id")
                .param("o", org).param("c", channel).param("d", destination).param("v", version).param("s", ok ? "SUCCEEDED" : "FAILED")
                .param("m", message).param("sim", simulated).query(UUID.class).single();
    }

    /** Opens (or refreshes) a warning alert, de-duplicated by key while unresolved. */
    public void raiseWarning(UUID org, String dedupKey, String title, String detail, String suggestion, String link) {
        db.sql("""
                insert into alert(org_id, dedup_key, severity, title, detail, suggestion, link) values (:o, :k, 'WARNING', :t, :d, :s, :l)
                on conflict (org_id, dedup_key) where status <> 'RESOLVED' do update set detail = excluded.detail, updated_at = now()""")
                .param("o", org).param("k", dedupKey).param("t", title).param("d", detail).param("s", suggestion).param("l", link).update();
    }

    // ------------------------------------------------------------------ partner sharing

    /** Active partner organizations (outside RLS; caller decides on bypass). */
    public List<PartnerOrg> activePartners() {
        return db.sql("select id, name, country from organization where kind = 'PARTNER' and status = 'ACTIVE' order by name").query(PartnerOrg.class).list();
    }

    public record PartnerName(String name, String slug) {}

    public Optional<PartnerName> activePartner(UUID partner) {
        return db.sql("select name, slug from organization where id = :p and kind = 'PARTNER' and status = 'ACTIVE'").param("p", partner).query(PartnerName.class).optional();
    }

    public record SharingRow(UUID id, UUID partnerId, String partnerName, String status, String scopes, boolean allChannels, UUID destinationId,
                             String destinationName, String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt, OffsetDateTime revokedAt,
                             OffsetDateTime expiresAt, long readsLast7Days, OffsetDateTime lastReadAt) {}

    /** Sharings of the project, active first, with partner read counts from the audit log. */
    public List<SharingRow> sharings(UUID org) {
        return db.sql("""
                select l.id, p.id as partner_id, p.name as partner_name, l.status, l.scopes, l.all_channels, l.destination_id, d.name as destination_name,
                  l.note, l.created_at, l.accepted_at, l.revoked_at, l.expires_at,
                  (select count(*) from audit_event a where a.org_id = l.owner_org_id and a.via_org_id = l.partner_org_id
                     and a.action = 'partner.read' and a.created_at > now() - interval '7 days') as reads_last7_days,
                  (select max(a.created_at) from audit_event a where a.org_id = l.owner_org_id and a.via_org_id = l.partner_org_id and a.action = 'partner.read') as last_read_at
                from partner_link l join organization p on p.id = l.partner_org_id
                left join destination d on d.org_id = l.owner_org_id and d.id = l.destination_id
                where l.owner_org_id = :o order by case l.status when 'ACTIVE' then 0 when 'INVITED' then 1 else 2 end, l.created_at desc""")
                .param("o", org).query(SharingRow.class).list();
    }

    public List<Named> activeChannels(UUID org) {
        return db.sql("select id, name from channel where org_id = :o and status <> 'ARCHIVED' order by name").param("o", org).query(Named.class).list();
    }

    public List<Named> sharedChannels(UUID org, UUID link) {
        return db.sql("""
                select c.id, c.name from partner_link_channel x join channel c on c.org_id = x.org_id and c.id = x.channel_id
                where x.link_id = :l and x.org_id = :o order by c.name""").param("l", link).param("o", org).query(Named.class).list();
    }

    public boolean sharesWith(UUID org, UUID partner) {
        return db.sql("select count(*) from partner_link where owner_org_id = :o and partner_org_id = :p and status <> 'REVOKED'")
                .param("o", org).param("p", partner).query(Integer.class).single() > 0;
    }

    public UUID insertSharing(UUID org, UUID partner, boolean all, String scopes, UUID destination, String note, UUID createdBy, OffsetDateTime expiresAt) {
        return db.sql("""
                insert into partner_link(owner_org_id, partner_org_id, all_channels, scopes, destination_id, note, created_by, expires_at)
                values (:o, :p, :all, :s, :d, :n, :u, :e) returning id""")
                .param("o", org).param("p", partner).param("all", all).param("s", scopes).param("d", destination)
                .param("n", note).param("u", createdBy).param("e", expiresAt).query(UUID.class).single();
    }

    /** False when the sharing does not exist or was revoked. */
    public boolean updateSharing(UUID org, UUID link, boolean all, String scopes, UUID destination, String note, OffsetDateTime expiresAt) {
        return db.sql("""
                update partner_link set all_channels = :all, scopes = :s, destination_id = :d, note = :n, expires_at = :e
                where id = :id and owner_org_id = :o and status <> 'REVOKED'""")
                .param("all", all).param("s", scopes).param("d", destination).param("n", note).param("e", expiresAt)
                .param("id", link).param("o", org).update() > 0;
    }

    public void clearSharedChannels(UUID org, UUID link) {
        db.sql("delete from partner_link_channel where link_id = :l and org_id = :o").param("l", link).param("o", org).update();
    }

    public void addSharedChannel(UUID org, UUID link, UUID channel) {
        db.sql("insert into partner_link_channel(link_id, org_id, channel_id) values (:l, :o, :c)").param("l", link).param("o", org).param("c", channel).update();
    }

    public boolean revokeSharing(UUID org, UUID link, UUID by) {
        return db.sql("update partner_link set status = 'REVOKED', revoked_by = :u, revoked_at = now() where id = :id and owner_org_id = :o and status <> 'REVOKED'")
                .param("u", by).param("id", link).param("o", org).update() > 0;
    }

    public boolean destinationExists(UUID org, UUID destination) {
        return db.sql("select count(*) from destination where org_id = :o and id = :d").param("o", org).param("d", destination).query(Integer.class).single() > 0;
    }

    public boolean sharesAllChannels(UUID owner, UUID link) {
        return db.sql("select all_channels from partner_link where owner_org_id = :o and id = :l").param("o", owner).param("l", link).query(Boolean.class).single();
    }

    public record Admin(String email, String name) {}

    public List<Admin> partnerAdmins(UUID partnerOrg) {
        return db.sql("select u.email, u.name from membership m join app_user u on u.id = m.user_id where m.org_id = :p and m.role = 'PARTNER_ADMIN'")
                .param("p", partnerOrg).query(Admin.class).list();
    }
}
