package com.faststudio.v1.services.partner;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.partner.PartnerInterceptor;
import com.faststudio.v1.core.partner.PartnerScopes;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.partner.PartnerDto.*;
import com.faststudio.v1.repositories.partner.PartnerRepository;
import com.faststudio.v1.services.media.MediaSigner;
import com.faststudio.v1.services.playout.PlayoutServiceV1;
import com.faststudio.v1.services.programming.EpgServiceV1;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * What partners read about channels customers shared with them. Read-only, fixed shapes.
 * {@link PartnerInterceptor} has already checked the sharing, the scope and the channel, and switched row-level
 * security to the owner in a read-only transaction; this service only shapes the answer.
 */
@Service
public class PartnerApiServiceV1 {
    private final Data data;
    private final PartnerRepository repository;
    private final EpgServiceV1 epg;
    private final MediaSigner signer;
    private final Audit audit;
    private final String appUrl;

    public PartnerApiServiceV1(Data data, PartnerRepository repository, EpgServiceV1 epg, MediaSigner signer, Audit audit,
                               @Value("${faststudio.app-url}") String appUrl) {
        this.data = data;
        this.repository = repository;
        this.epg = epg;
        this.signer = signer;
        this.audit = audit;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ sharings

    /** Sharings from customers: waiting for acceptance or active (revoked ones disappear). */
    public List<Shared> links(PartnerInterceptor.Ctx c) {
        return repository.links(c.partnerId()).stream().map(this::shared).toList();
    }

    private Shared shared(PartnerRepository.LinkRow l) {
        return TenantScope.as(l.ownerId(), () -> {
            List<Named> channels = l.allChannels() ? repository.ownerActiveChannels(l.ownerId()) : repository.linkChannels(l.id());
            Destination d = l.destinationId() == null ? null : repository.destination(l.ownerId(), l.destinationId()).orElse(null);
            return new Shared(l.id(), l.ownerId(), l.ownerSlug(), l.ownerName(), l.status(), List.copyOf(PartnerScopes.parse(l.scopes())), l.allChannels(),
                    channels, d, l.note(), l.createdAt(), l.acceptedAt(), l.expiresAt());
        });
    }

    /** A partner admin accepts a sharing; until then nothing can be read. The audit row goes to the owner's log. */
    public Map<String, String> accept(PartnerInterceptor.Ctx c, UUID linkId) {
        if (c.userId() == null || !c.admin()) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a partner admin can accept a sharing.");
        UUID owner = repository.accept(c.partnerId(), linkId, c.userId()).orElseThrow(() -> ApiException.notFound("Sharing waiting for acceptance"));
        audit.partner(owner, c.partnerId(), c.userId(), "partner.accept", "partner_link", linkId, c.partnerName() + " accepted the sharing");
        return Map.of("status", "ACTIVE");
    }

    // ------------------------------------------------------------------ shared data

    private static PartnerChannel channel(Data.Channel c) {
        return new PartnerChannel(c.id(), c.slug(), c.name(), c.description(), c.genre(), c.language(), c.timezone(), c.color(),
                c.territoryList(), "LIVE".equals(c.status()));
    }

    public List<PartnerChannel> channels(PartnerInterceptor.Link l) {
        Set<UUID> shared = l.allChannels() ? null : repository.linkChannelIds(l.id());
        return data.channels(l.ownerId()).stream().filter(c -> shared == null || shared.contains(c.id())).map(PartnerApiServiceV1::channel).toList();
    }

    public PartnerChannel channel(PartnerInterceptor.Link l, UUID channelId) {
        return channel(data.channel(l.ownerId(), channelId));
    }

    public List<Programme> epg(PartnerInterceptor.Link l, UUID channelId, int hours) {
        Instant from = Instant.now().minusSeconds(3600);
        return epg.guide(l.ownerId(), data.channel(l.ownerId(), channelId), from, from.plusSeconds(Math.min(Math.max(hours, 1), 168) * 3600L)).stream()
                .map(p -> new Programme(p.title(), p.description(), p.genre(), p.start(), p.end(), p.now(), p.adBreaks(),
                        p.mediaKey() == null ? null : appUrl + signer.url(p.mediaKey(), "thumb.jpg"))).toList();
    }

    public String xmltv(PartnerInterceptor.Link l, UUID channelId) {
        return epg.xmltv(l.ownerId(), l.ownerSlug(), data.channel(l.ownerId(), channelId));
    }

    /** How the partner starts a viewer's stream: one playback session per viewer, with the partner's own delivery settings. */
    public Stream stream(PartnerInterceptor.Link l, UUID channelId) {
        Data.Channel ch = data.channel(l.ownerId(), channelId);
        if (l.destinationId() == null) throw ApiException.conflict("NO_DESTINATION",
                "The content owner hasn't set up delivery for your platform yet (no destination chosen for this sharing).");
        Destination d = repository.destination(l.ownerId(), l.destinationId()).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("org", l.ownerSlug());
        body.put("channel", ch.slug());
        body.put("destinationId", l.destinationId());
        body.put("country", "<the viewer's 2-letter country code>");
        return new Stream("Start one session per viewer: POST this body; the answer's playlistUrl is the HLS stream to play.",
                "POST", appUrl + "/play/sessions", body, d);
    }

    public List<AdBreak> breaks(PartnerInterceptor.Link l, UUID channelId, int hours) {
        Data.Channel ch = data.channel(l.ownerId(), channelId);
        if (ch.publishedVersionId() == null) return List.of();
        Instant now = Instant.now();
        return data.entries(l.ownerId(), ch.publishedVersionId(), now, now.plusSeconds(Math.min(Math.max(hours, 1), 48) * 3600L)).stream()
                .filter(Data.Entry::isBreak)
                .map(b -> new AdBreak(b.id(), b.start(), b.end(), b.durationMs(), b.ownership(), PlayoutServiceV1.scteHex(b, true), PlayoutServiceV1.scteHex(b, false)))
                .toList();
    }

    /** Numbers for the partner's own destination only: never other platforms' viewers. */
    public Delivery delivery(PartnerInterceptor.Link l, UUID channelId, int days) {
        int d = Math.min(Math.max(days, 1), 90);
        if (l.destinationId() == null) return new Delivery(null, d, 0, 0, 0, List.of(), "No destination is assigned to your platform yet, so there is nothing to count.");
        String dest = repository.destination(l.ownerId(), l.destinationId()).map(Destination::name).orElseThrow();
        UUID o = l.ownerId(), dst = l.destinationId();
        return new Delivery(dest, d, repository.sessions(o, channelId, dst, d), repository.adEvents(o, channelId, dst, d, "IMPRESSION"),
                repository.adEvents(o, channelId, dst, d, "COMPLETE"), repository.byDay(o, channelId, dst, d),
                "Counted from viewers who watched through your platform's destination only.");
    }
}
