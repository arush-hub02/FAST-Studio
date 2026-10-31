package com.faststudio.v1.controllers.partner;

import com.faststudio.v1.dto.programming.ScheduleDto.Programme;
import com.faststudio.v1.services.programming.EpgServiceV1;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.partner.PartnerInterceptor;
import com.faststudio.v1.core.partner.PartnerScope;
import com.faststudio.v1.core.partner.PartnerScopes;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.services.media.MediaSigner;
import com.faststudio.v1.services.playout.PlayoutServiceV1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * What partners (and their API clients) read. Everything here is read-only and returns fixed shapes, never internal
 * objects, so new internal fields can't leak. PartnerInterceptor has already checked sharing, scope and channel.
 */
@RestController
@RequestMapping("/api/partners/{partner}")
public class PartnerApiControllerV1 {
    private final Data data;
    private final EpgServiceV1 epg;
    private final Audit audit;
    private final MediaSigner signer;
    private final String appUrl;

    public PartnerApiControllerV1(Data data, Audit audit, MediaSigner signer, @Value("${faststudio.app-url}") String appUrl, EpgServiceV1 epg) {
        this.epg = epg;
        this.data = data;
        this.audit = audit;
        this.signer = signer;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    static PartnerInterceptor.Ctx ctx(jakarta.servlet.http.HttpServletRequest req) {
        return (PartnerInterceptor.Ctx) req.getAttribute(PartnerInterceptor.ATTR);
    }

    // ------------------------------------------------------------------ what is shared with us

    public record Named(UUID id, String name) {}

    public record Destination(String name, String adMode, String markerFormat, int maxAdSecondsPerHour) {}

    public record Shared(UUID id, UUID ownerId, String ownerSlug, String ownerName, String status, List<String> scopes, boolean allChannels,
                         List<Named> channels, Destination destination, String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt,
                         OffsetDateTime expiresAt) {}

    record LinkRow(UUID id, UUID ownerId, String ownerSlug, String ownerName, String status, String scopes, boolean allChannels,
                   UUID destinationId, String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt, OffsetDateTime expiresAt) {}

    /** Sharings from customers: waiting for acceptance or active (revoked ones disappear). */
    @GetMapping("/links")
    List<Shared> links(jakarta.servlet.http.HttpServletRequest req) {
        var c = ctx(req);
        return data.db.sql("""
                select l.id, o.id as owner_id, o.slug as owner_slug, o.name as owner_name, l.status, l.scopes, l.all_channels, l.destination_id,
                  l.note, l.created_at, l.accepted_at, l.expires_at
                from partner_link l join organization o on o.id = l.owner_org_id
                where l.partner_org_id = :p and l.status <> 'REVOKED' order by l.status, o.name""")
                .param("p", c.partnerId()).query(LinkRow.class).list().stream().map(this::shared).toList();
    }

    Shared shared(LinkRow l) {
        return TenantScope.as(l.ownerId(), () -> {
            List<Named> channels = l.allChannels()
                    ? data.db.sql("select id, name from channel where org_id = :o and status <> 'ARCHIVED' order by name").param("o", l.ownerId()).query(Named.class).list()
                    : data.db.sql("select c.id, c.name from partner_link_channel x join channel c on c.org_id = x.org_id and c.id = x.channel_id where x.link_id = :l order by c.name")
                    .param("l", l.id()).query(Named.class).list();
            Destination d = l.destinationId() == null ? null : data.db.sql("select name, ad_mode, marker_format, max_ad_seconds_per_hour from destination where org_id = :o and id = :d")
                    .param("o", l.ownerId()).param("d", l.destinationId()).query(Destination.class).optional().orElse(null);
            return new Shared(l.id(), l.ownerId(), l.ownerSlug(), l.ownerName(), l.status(), List.copyOf(PartnerScopes.parse(l.scopes())), l.allChannels(),
                    channels, d, l.note(), l.createdAt(), l.acceptedAt(), l.expiresAt());
        });
    }

    /** A partner admin accepts a sharing; until then nothing can be read. */
    @PostMapping("/links/{linkId}/accept")     // one atomic update; the audit row goes to the owner's log, outside the partner's scope
    Map<String, String> accept(@PathVariable UUID linkId, jakarta.servlet.http.HttpServletRequest req) {
        var c = ctx(req);
        if (c.userId() == null || !c.admin()) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a partner admin can accept a sharing.");
        UUID owner = data.db.sql("update partner_link set status = 'ACTIVE', accepted_by = :u, accepted_at = now() where id = :l and partner_org_id = :p and status = 'INVITED' returning owner_org_id")
                .param("u", c.userId()).param("l", linkId).param("p", c.partnerId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Sharing waiting for acceptance"));
        audit.partner(owner, c.partnerId(), c.userId(), "partner.accept", "partner_link", linkId, c.partnerName() + " accepted the sharing");
        return Map.of("status", "ACTIVE");
    }

    // ------------------------------------------------------------------ shared data (read-only)

    public record PartnerChannel(UUID id, String slug, String name, String description, String genre, String language, String timezone,
                                 String color, List<String> countries, boolean live) {}

    static PartnerChannel channel(Data.Channel c) {
        return new PartnerChannel(c.id(), c.slug(), c.name(), c.description(), c.genre(), c.language(), c.timezone(), c.color(),
                c.territoryList(), "LIVE".equals(c.status()));
    }

    @GetMapping("/sources/{owner}/channels")
    @PartnerScope(PartnerScopes.CHANNELS)
    @Transactional(readOnly = true)
    List<PartnerChannel> channels(jakarta.servlet.http.HttpServletRequest req) {
        var l = ctx(req).requireLink();
        Set<UUID> shared = l.allChannels() ? null : new HashSet<>(data.db.sql("select channel_id from partner_link_channel where link_id = :l")
                .param("l", l.id()).query(UUID.class).list());
        return data.channels(l.ownerId()).stream().filter(c -> shared == null || shared.contains(c.id())).map(PartnerApiControllerV1::channel).toList();
    }

    @GetMapping("/sources/{owner}/channels/{channelId}")
    @PartnerScope(PartnerScopes.CHANNELS)
    @Transactional(readOnly = true)
    PartnerChannel channel(@PathVariable UUID channelId, jakarta.servlet.http.HttpServletRequest req) {
        return channel(data.channel(ctx(req).requireLink().ownerId(), channelId));
    }

    public record Programme(String title, String description, String genre, Instant start, Instant end, boolean onNow, int adBreaks, String image) {}

    @GetMapping("/sources/{owner}/channels/{channelId}/epg")
    @PartnerScope(PartnerScopes.EPG)
    @Transactional(readOnly = true)
    List<Programme> epg(@PathVariable UUID channelId, @RequestParam(defaultValue = "24") int hours, jakarta.servlet.http.HttpServletRequest req) {
        UUID owner = ctx(req).requireLink().ownerId();
        Instant from = Instant.now().minusSeconds(3600);
        return epg.guide(owner, data.channel(owner, channelId), from, from.plusSeconds(Math.min(Math.max(hours, 1), 168) * 3600L)).stream()
                .map(p -> new Programme(p.title(), p.description(), p.genre(), p.start(), p.end(), p.now(), p.adBreaks(),
                        p.mediaKey() == null ? null : appUrl + signer.url(p.mediaKey(), "thumb.jpg"))).toList();
    }

    @GetMapping(value = "/sources/{owner}/channels/{channelId}/epg.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @PartnerScope(PartnerScopes.EPG)
    @Transactional(readOnly = true)
    String xmltv(@PathVariable UUID channelId, jakarta.servlet.http.HttpServletRequest req) {
        var l = ctx(req).requireLink();
        return epg.xmltv(l.ownerId(), l.ownerSlug(), data.channel(l.ownerId(), channelId));
    }

    public record Stream(String howTo, String method, String url, Map<String, Object> body, Destination destination) {}

    /** How the partner starts a viewer's stream: one playback session per viewer, with the partner's own delivery settings. */
    @GetMapping("/sources/{owner}/channels/{channelId}/stream")
    @PartnerScope(PartnerScopes.STREAM)
    @Transactional(readOnly = true)
    Stream stream(@PathVariable UUID channelId, jakarta.servlet.http.HttpServletRequest req) {
        var l = ctx(req).requireLink();
        Data.Channel ch = data.channel(l.ownerId(), channelId);
        if (l.destinationId() == null) throw ApiException.conflict("NO_DESTINATION",
                "The content owner hasn't set up delivery for your platform yet (no destination chosen for this sharing).");
        Destination d = data.db.sql("select name, ad_mode, marker_format, max_ad_seconds_per_hour from destination where org_id = :o and id = :d")
                .param("o", l.ownerId()).param("d", l.destinationId()).query(Destination.class).single();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("org", l.ownerSlug());
        body.put("channel", ch.slug());
        body.put("destinationId", l.destinationId());
        body.put("country", "<the viewer's 2-letter country code>");
        return new Stream("Start one session per viewer: POST this body; the answer's playlistUrl is the HLS stream to play.",
                "POST", appUrl + "/play/sessions", body, d);
    }

    public record AdBreak(UUID id, Instant start, Instant end, long durationMs, String ownership, String scte35Out, String scte35In) {}

    @GetMapping("/sources/{owner}/channels/{channelId}/breaks")
    @PartnerScope(PartnerScopes.MARKERS)
    @Transactional(readOnly = true)
    List<AdBreak> breaks(@PathVariable UUID channelId, @RequestParam(defaultValue = "6") int hours, jakarta.servlet.http.HttpServletRequest req) {
        UUID owner = ctx(req).requireLink().ownerId();
        Data.Channel ch = data.channel(owner, channelId);
        if (ch.publishedVersionId() == null) return List.of();
        Instant now = Instant.now();
        return data.entries(owner, ch.publishedVersionId(), now, now.plusSeconds(Math.min(Math.max(hours, 1), 48) * 3600L)).stream()
                .filter(Data.Entry::isBreak)
                .map(b -> new AdBreak(b.id(), b.start(), b.end(), b.durationMs(), b.ownership(), PlayoutServiceV1.scteHex(b, true), PlayoutServiceV1.scteHex(b, false)))
                .toList();
    }

    public record Day(LocalDate day, long viewerSessions, long adImpressions) {}

    public record Delivery(String destination, int days, long viewerSessions, long adImpressions, long adCompletes, List<Day> byDay, String note) {}

    /** Numbers for the partner's own destination only: never other platforms' viewers. */
    @GetMapping("/sources/{owner}/channels/{channelId}/delivery")
    @PartnerScope(PartnerScopes.DELIVERY)
    @Transactional(readOnly = true)
    Delivery delivery(@PathVariable UUID channelId, @RequestParam(defaultValue = "7") int days, jakarta.servlet.http.HttpServletRequest req) {
        var l = ctx(req).requireLink();
        int d = Math.min(Math.max(days, 1), 90);
        if (l.destinationId() == null) return new Delivery(null, d, 0, 0, 0, List.of(), "No destination is assigned to your platform yet, so there is nothing to count.");
        String dest = data.db.sql("select name from destination where org_id = :o and id = :d").param("o", l.ownerId()).param("d", l.destinationId()).query(String.class).single();
        var p = Map.<String, Object>of("o", l.ownerId(), "c", channelId, "d", l.destinationId(), "n", d);
        long sessions = count("select count(*) from playback_session where org_id = :o and channel_id = :c and destination_id = :d and created_at > now() - make_interval(days => :n)", p);
        long imps = count("""
                select count(*) from ad_event e join playback_session s on s.org_id = e.org_id and s.id = e.session_id
                where e.org_id = :o and e.channel_id = :c and s.destination_id = :d and e.event = 'IMPRESSION' and e.occurred_at > now() - make_interval(days => :n)""", p);
        long completes = count("""
                select count(*) from ad_event e join playback_session s on s.org_id = e.org_id and s.id = e.session_id
                where e.org_id = :o and e.channel_id = :c and s.destination_id = :d and e.event = 'COMPLETE' and e.occurred_at > now() - make_interval(days => :n)""", p);
        List<Day> byDay = data.db.sql("""
                select d::date as day,
                  (select count(*) from playback_session s where s.org_id = :o and s.channel_id = :c and s.destination_id = :d and s.created_at::date = d::date) as viewer_sessions,
                  (select count(*) from ad_event e join playback_session s on s.org_id = e.org_id and s.id = e.session_id
                     where e.org_id = :o and e.channel_id = :c and s.destination_id = :d and e.event = 'IMPRESSION' and e.occurred_at::date = d::date) as ad_impressions
                from generate_series(current_date - (:n - 1), current_date, interval '1 day') d order by d""")
                .params(p).query(Day.class).list();
        return new Delivery(dest, d, sessions, imps, completes, byDay, "Counted from viewers who watched through your platform's destination only.");
    }

    private long count(String sql, Map<String, Object> p) {
        return data.db.sql(sql).params(p).query(Long.class).single();
    }
}
