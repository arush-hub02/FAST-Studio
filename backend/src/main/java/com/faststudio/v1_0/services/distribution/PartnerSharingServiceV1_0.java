package com.faststudio.v1_0.services.distribution;

import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.partner.PartnerScopes;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.core.tenant.TenantScope;
import com.faststudio.v1_0.dto.distribution.DistributionDto.*;
import com.faststudio.v1_0.repositories.distribution.DistributionRepository;
import com.faststudio.v1_0.services.mail.MailServiceV1_0;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer side of partner sharing: share chosen channels and scopes with a partner organization, change it, or stop
 * sharing. A new sharing waits for the partner to accept; stopping takes effect on the partner's next request.
 * Partner organizations only ever read what is shared (see core/partner).
 */
@Service
public class PartnerSharingServiceV1_0 {
    private final Data data;
    private final DistributionRepository repository;
    private final MailServiceV1_0 mail;
    private final Audit audit;

    public PartnerSharingServiceV1_0(Data data, DistributionRepository repository, MailServiceV1_0 mail, Audit audit) {
        this.data = data;
        this.repository = repository;
        this.mail = mail;
        this.audit = audit;
    }

    /** Partner names on this platform and the shareable scopes. Organization names are readable across tenants by design. */
    public Directory directory(Tenant t) {
        return new Directory(TenantScope.bypass(repository::activePartners),
                PartnerScopes.ALL.entrySet().stream().map(e -> new Scope(e.getKey(), e.getValue())).toList());
    }

    public List<Sharing> list(Tenant t) {
        if (t.partnerOrg()) throw ApiException.notFound("Page");
        return repository.sharings(t.orgId()).stream().map(r -> new Sharing(r.id(), r.partnerId(), r.partnerName(), r.status(),
                List.copyOf(PartnerScopes.parse(r.scopes())), r.allChannels(), channels(t, r.id(), r.allChannels()), r.destinationId(), r.destinationName(),
                r.note(), r.createdAt(), r.acceptedAt(), r.revokedAt(), r.expiresAt(), r.readsLast7Days(), r.lastReadAt())).toList();
    }

    private List<Named> channels(Tenant t, UUID link, boolean all) {
        return all ? repository.activeChannels(t.orgId()) : repository.sharedChannels(t.orgId(), link);
    }

    @Transactional
    public Sharing share(Tenant t, ShareRequest r) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        if (t.partnerOrg()) throw ApiException.notFound("Page");
        if (r.partnerOrgId() == null) throw ApiException.bad("Choose a partner.");
        var partner = TenantScope.bypass(() -> repository.activePartner(r.partnerOrgId())).orElseThrow(() -> ApiException.notFound("Partner"));
        String scopes = PartnerScopes.normalize(r.scopes());
        if (repository.sharesWith(t.orgId(), r.partnerOrgId()))
            throw ApiException.conflict("ALREADY_SHARED", "You already share with " + partner.name() + ". Change that sharing instead.");
        UUID id = repository.insertSharing(t.orgId(), r.partnerOrgId(), r.all(), scopes, destination(t, r.destinationId()),
                r.note() == null ? "" : r.note().strip(), t.userId(), r.expiresAt());
        setChannels(t, id, r);
        audit.log(t, "partner.share", "partner_link", id, "shared with " + partner.name() + ": " + scopes);
        notifyPartner(t, r.partnerOrgId(), partner.name(), partner.slug(), id, scopes);
        return find(t, id);
    }

    @Transactional
    public Sharing update(Tenant t, UUID linkId, ShareRequest r) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        String scopes = PartnerScopes.normalize(r.scopes());
        if (!repository.updateSharing(t.orgId(), linkId, r.all(), scopes, destination(t, r.destinationId()),
                r.note() == null ? "" : r.note().strip(), r.expiresAt())) throw ApiException.notFound("Sharing");
        repository.clearSharedChannels(t.orgId(), linkId);
        setChannels(t, linkId, r);
        audit.log(t, "partner.share_update", "partner_link", linkId, scopes);
        return find(t, linkId);
    }

    /** Stop sharing: the partner loses access on its very next request. */
    public Map<String, String> revoke(Tenant t, UUID linkId) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        if (!repository.revokeSharing(t.orgId(), linkId, t.userId())) throw ApiException.notFound("Sharing");
        audit.log(t, "partner.revoke", "partner_link", linkId, "stopped sharing");
        return Map.of("status", "REVOKED");
    }

    private Sharing find(Tenant t, UUID id) {
        return list(t).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    /** The destination, if given, must belong to this project. */
    private UUID destination(Tenant t, UUID d) {
        if (d == null) return null;
        if (!repository.destinationExists(t.orgId(), d)) throw ApiException.notFound("Destination");
        return d;
    }

    private void setChannels(Tenant t, UUID link, ShareRequest r) {
        if (r.all()) return;
        List<UUID> ids = r.channelIds() == null ? List.of() : r.channelIds().stream().distinct().toList();
        if (ids.isEmpty()) throw ApiException.bad("Choose at least one channel, or share all channels.");
        for (UUID c : ids) repository.addSharedChannel(t.orgId(), link, data.channel(t.orgId(), c).id());   // 404 for another company's channel
    }

    /** Emails every partner admin that something was shared and where to accept it. */
    private void notifyPartner(Tenant t, UUID partnerOrg, String partnerName, String partnerSlug, UUID link, String scopes) {
        String channels = String.join(", ", channels(t, link, repository.sharesAllChannels(link)).stream().map(Named::name).toList());
        for (var a : repository.partnerAdmins(partnerOrg))
            mail.send(partnerOrg, "partner_shared", a.email(), Map.of("name", a.name(), "ownerName", t.orgName(), "partnerName", partnerName,
                    "channels", channels, "scopes", String.join(", ", PartnerScopes.parse(scopes).stream().map(PartnerScopes.ALL::get).toList()),
                    "acceptUrl", mail.appUrl() + "/" + partnerSlug));
    }
}
