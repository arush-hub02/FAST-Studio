package com.faststudio.v1_0.controllers.studio.distribution;

import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.partner.PartnerScopes;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.core.tenant.TenantScope;
import com.faststudio.v1_0.services.mail.MailServiceV1_0;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Customer side: share chosen channels and scopes with a partner organization, change it, or stop sharing.
 * A new sharing waits for the partner to accept; stopping takes effect on the partner's next request.
 */
@RestController
@RequestMapping("/api/orgs/{org}")
public class PartnerSharingControllerV1_0 {
    private final Data data;
    private final Audit audit;
    private final MailServiceV1_0 mail;

    public PartnerSharingControllerV1_0(Data data, Audit audit, MailServiceV1_0 mail) {
        this.data = data;
        this.audit = audit;
        this.mail = mail;
    }

    public record PartnerOrg(UUID id, String name, String country) {}

    public record Scope(String key, String meaning) {}

    public record Directory(List<PartnerOrg> partners, List<Scope> scopes) {}

    /** Partner organizations on this platform (names only) and what can be shared. */
    @GetMapping("/partners/directory")
    Directory directory(Tenant t) {
        return new Directory(TenantScope.bypass(() -> data.db.sql("select id, name, country from organization where kind = 'PARTNER' and status = 'ACTIVE' order by name")
                .query(PartnerOrg.class).list()), PartnerScopes.ALL.entrySet().stream().map(e -> new Scope(e.getKey(), e.getValue())).toList());
    }

    public record Named(UUID id, String name) {}

    public record Sharing(UUID id, UUID partnerId, String partnerName, String status, List<String> scopes, boolean allChannels, List<Named> channels,
                          UUID destinationId, String destinationName, String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt,
                          OffsetDateTime revokedAt, OffsetDateTime expiresAt, long readsLast7Days, OffsetDateTime lastReadAt) {}

    record Row(UUID id, UUID partnerId, String partnerName, String status, String scopes, boolean allChannels, UUID destinationId, String destinationName,
               String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt, OffsetDateTime revokedAt, OffsetDateTime expiresAt,
               long readsLast7Days, OffsetDateTime lastReadAt) {}

    @GetMapping("/partners")
    List<Sharing> list(Tenant t) {
        if (t.partnerOrg()) throw ApiException.notFound("Page");
        return data.db.sql("""
                select l.id, p.id as partner_id, p.name as partner_name, l.status, l.scopes, l.all_channels, l.destination_id, d.name as destination_name,
                  l.note, l.created_at, l.accepted_at, l.revoked_at, l.expires_at,
                  (select count(*) from audit_event a where a.org_id = l.owner_org_id and a.via_org_id = l.partner_org_id
                     and a.action = 'partner.read' and a.created_at > now() - interval '7 days') as reads_last7_days,
                  (select max(a.created_at) from audit_event a where a.org_id = l.owner_org_id and a.via_org_id = l.partner_org_id and a.action = 'partner.read') as last_read_at
                from partner_link l join organization p on p.id = l.partner_org_id
                left join destination d on d.org_id = l.owner_org_id and d.id = l.destination_id
                where l.owner_org_id = :o order by case l.status when 'ACTIVE' then 0 when 'INVITED' then 1 else 2 end, l.created_at desc""")
                .param("o", t.orgId()).query(Row.class).list().stream().map(r -> new Sharing(r.id(), r.partnerId(), r.partnerName(), r.status(),
                        List.copyOf(PartnerScopes.parse(r.scopes())), r.allChannels(), channels(t, r.id(), r.allChannels()), r.destinationId(), r.destinationName(),
                        r.note(), r.createdAt(), r.acceptedAt(), r.revokedAt(), r.expiresAt(), r.readsLast7Days(), r.lastReadAt())).toList();
    }

    private List<Named> channels(Tenant t, UUID link, boolean all) {
        return all ? data.db.sql("select id, name from channel where org_id = :o and status <> 'ARCHIVED' order by name").param("o", t.orgId()).query(Named.class).list()
                : data.db.sql("select c.id, c.name from partner_link_channel x join channel c on c.org_id = x.org_id and c.id = x.channel_id where x.link_id = :l and x.org_id = :o order by c.name")
                .param("l", link).param("o", t.orgId()).query(Named.class).list();
    }

    public record ShareRequest(UUID partnerOrgId, Boolean allChannels, List<UUID> channelIds, @NotNull List<String> scopes, UUID destinationId,
                               String note, OffsetDateTime expiresAt) {
        boolean all() { return Boolean.TRUE.equals(allChannels); }
    }

    @PostMapping("/partners")
    @Transactional
    Sharing share(Tenant t, @Valid @RequestBody ShareRequest r) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        if (t.partnerOrg()) throw ApiException.notFound("Page");
        if (r.partnerOrgId() == null) throw ApiException.bad("Choose a partner.");
        record P(String name, String slug) {}
        P partner = TenantScope.bypass(() -> data.db.sql("select name, slug from organization where id = :p and kind = 'PARTNER' and status = 'ACTIVE'")
                .param("p", r.partnerOrgId()).query(P.class).optional()).orElseThrow(() -> ApiException.notFound("Partner"));
        String scopes = PartnerScopes.normalize(r.scopes());
        if (data.db.sql("select count(*) from partner_link where owner_org_id = :o and partner_org_id = :p and status <> 'REVOKED'")
                .param("o", t.orgId()).param("p", r.partnerOrgId()).query(Integer.class).single() > 0)
            throw ApiException.conflict("ALREADY_SHARED", "You already share with " + partner.name() + ". Change that sharing instead.");
        UUID id = data.db.sql("""
                insert into partner_link(owner_org_id, partner_org_id, all_channels, scopes, destination_id, note, created_by, expires_at)
                values (:o,:p,:all,:s,:d,:n,:u,:e) returning id""")
                .param("o", t.orgId()).param("p", r.partnerOrgId()).param("all", r.all()).param("s", scopes).param("d", destination(t, r.destinationId()))
                .param("n", r.note() == null ? "" : r.note().strip()).param("u", t.userId()).param("e", r.expiresAt()).query(UUID.class).single();
        setChannels(t, id, r);
        audit.log(t, "partner.share", "partner_link", id, "shared with " + partner.name() + ": " + scopes);
        notifyPartner(t, r.partnerOrgId(), partner.name(), partner.slug(), id, scopes);
        return list(t).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @PutMapping("/partners/{linkId}")
    @Transactional
    Sharing update(Tenant t, @PathVariable UUID linkId, @Valid @RequestBody ShareRequest r) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        String scopes = PartnerScopes.normalize(r.scopes());
        int n = data.db.sql("""
                update partner_link set all_channels = :all, scopes = :s, destination_id = :d, note = :n, expires_at = :e
                where id = :id and owner_org_id = :o and status <> 'REVOKED'""")
                .param("all", r.all()).param("s", scopes).param("d", destination(t, r.destinationId())).param("n", r.note() == null ? "" : r.note().strip())
                .param("e", r.expiresAt()).param("id", linkId).param("o", t.orgId()).update();
        if (n == 0) throw ApiException.notFound("Sharing");
        data.db.sql("delete from partner_link_channel where link_id = :l and org_id = :o").param("l", linkId).param("o", t.orgId()).update();
        setChannels(t, linkId, r);
        audit.log(t, "partner.share_update", "partner_link", linkId, scopes);
        return list(t).stream().filter(s -> s.id().equals(linkId)).findFirst().orElseThrow();
    }

    /** Stop sharing: the partner loses access on its very next request. */
    @PostMapping("/partners/{linkId}/revoke")
    Map<String, String> revoke(Tenant t, @PathVariable UUID linkId) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        int n = data.db.sql("update partner_link set status = 'REVOKED', revoked_by = :u, revoked_at = now() where id = :id and owner_org_id = :o and status <> 'REVOKED'")
                .param("u", t.userId()).param("id", linkId).param("o", t.orgId()).update();
        if (n == 0) throw ApiException.notFound("Sharing");
        audit.log(t, "partner.revoke", "partner_link", linkId, "stopped sharing");
        return Map.of("status", "REVOKED");
    }

    private UUID destination(Tenant t, UUID d) {
        if (d == null) return null;
        if (data.db.sql("select count(*) from destination where org_id = :o and id = :d").param("o", t.orgId()).param("d", d).query(Integer.class).single() == 0)
            throw ApiException.notFound("Destination");
        return d;
    }

    private void setChannels(Tenant t, UUID link, ShareRequest r) {
        if (r.all()) return;
        List<UUID> ids = r.channelIds() == null ? List.of() : r.channelIds().stream().distinct().toList();
        if (ids.isEmpty()) throw ApiException.bad("Choose at least one channel, or share all channels.");
        for (UUID c : ids) {
            Data.Channel ch = data.channel(t.orgId(), c);           // 404 for another company's channel
            data.db.sql("insert into partner_link_channel(link_id, org_id, channel_id) values (:l,:o,:c)").param("l", link).param("o", t.orgId()).param("c", ch.id()).update();
        }
    }

    record Admin(String email, String name) {}

    private void notifyPartner(Tenant t, UUID partnerOrg, String partnerName, String partnerSlug, UUID link, String scopes) {
        List<Admin> admins = data.db.sql("select u.email, u.name from membership m join app_user u on u.id = m.user_id where m.org_id = :p and m.role = 'PARTNER_ADMIN'")
                .param("p", partnerOrg).query(Admin.class).list();
        String channels = String.join(", ", channels(t, link, data.db.sql("select all_channels from partner_link where id = :l").param("l", link).query(Boolean.class).single())
                .stream().map(Named::name).toList());
        for (Admin a : admins)
            mail.send(partnerOrg, "partner_shared", a.email(), Map.of("name", a.name(), "ownerName", t.orgName(), "partnerName", partnerName,
                    "channels", channels, "scopes", String.join(", ", PartnerScopes.parse(scopes).stream().map(PartnerScopes.ALL::get).toList()),
                    "acceptUrl", mail.appUrl() + "/" + partnerSlug));
    }
}
