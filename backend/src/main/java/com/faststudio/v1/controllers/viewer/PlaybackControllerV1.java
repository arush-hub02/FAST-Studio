package com.faststudio.v1.controllers.viewer;

import com.faststudio.v1.dto.programming.ScheduleDto.Programme;
import com.faststudio.v1.services.programming.EpgServiceV1;

import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;
import com.faststudio.v1.services.ads.ViewerContextService;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.ads.sources.VastSource;
import com.faststudio.v1.services.playout.PlayoutServiceV1;
import com.faststudio.v1.services.rights.Rights;
import com.faststudio.v1.util.cloudflare.EdgeClient;
import com.faststudio.v1.util.cloudflare.EdgePublisher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/**
 * Public viewer-side API (no admin session). Starting a session returns the Worker's live stream URL, carrying a
 * signed session token; the Worker calls back here only for ad decisions and delivery events, once per break.
 * Production would derive the viewer's country from a trusted edge/geo-IP header; here it is an explicit, labelled simulation.
 */
@RestController
@RequestMapping("/play")
public class PlaybackControllerV1 {
    private final Data data;
    private final EpgServiceV1 epg;
    private final PlayoutServiceV1 playout;
    private final EdgeClient edge;
    private final EdgePublisher publisher;
    private final JsonMapper json;
    private final VastSource vast;
    private final ViewerContextService viewers;

    public PlaybackControllerV1(Data data, PlayoutServiceV1 playout, EdgeClient edge, EdgePublisher publisher, JsonMapper json, VastSource vast, ViewerContextService viewers, EpgServiceV1 epg) {
        this.epg = epg;
        this.vast = vast;
        this.viewers = viewers;
        this.data = data;
        this.playout = playout;
        this.edge = edge;
        this.publisher = publisher;
        this.json = json;
    }

    public record StartRequest(@NotBlank String org, @NotBlank String channel, UUID destinationId,
                               @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country, String viewerToken) {}

    public record Started(UUID sessionId, String playlistUrl, String adMode, String markerFormat, String destination,
                          String country, boolean simulatedLocation, Rights.Decision rightsNow) {}

    record OrgRow(UUID id, String status) {}

    record DestRow(UUID id, String name, String kind, String adMode, String markerFormat) {}

    /** What the Worker needs to know about a viewer, signed so it can trust it without a database. */
    record EdgeSession(UUID sid, String org, String channel, String country, String adMode, String markers, String destKind, long created) {}

    @PostMapping("/sessions")
    Started start(@Valid @RequestBody StartRequest r) {
        OrgRow org = data.db.sql("select id, status from organization where slug = :s").param("s", r.org()).query(OrgRow.class)
                .optional().orElseThrow(() -> ApiException.notFound("Channel"));
        TenantScope.set(org.id());          // everything below is read under this tenant's row-level security
        if (!"ACTIVE".equals(org.status())) throw ApiException.notFound("Channel");
        ViewerContextService.Verified viewer = viewers.verify(org.id(), r.viewerToken());
        UUID chId = data.db.sql("select id from channel where org_id = :o and slug = :s").param("o", org.id()).param("s", r.channel())
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Channel"));
        Channel ch = data.channel(org.id(), chId);
        if (!"LIVE".equals(ch.status()))
            throw new ApiException(HttpStatus.CONFLICT, "NOT_LIVE", "\"" + ch.name() + "\" isn't on air. Publish a schedule and press 'Go live' first.");
        String sql = "select id, name, kind, ad_mode, marker_format from destination where org_id = :o "
                + (r.destinationId() != null ? "and id = :d" : "and kind = 'OWNED_WEB' order by (ad_mode = 'SSAI') desc, created_at limit 1");
        var q = data.db.sql(sql).param("o", org.id());
        if (r.destinationId() != null) q = q.param("d", r.destinationId());
        DestRow d = q.query(DestRow.class).optional().orElseThrow(() -> ApiException.notFound("Destination"));
        String country = r.country().toUpperCase(Locale.ROOT);
        UUID sid = data.db.sql("insert into playback_session(org_id, channel_id, destination_id, country, ad_mode,viewer_key,audiences,personalized_until) values (:o,:c,:d,:k,:m,:vk,:aud,:until) returning id")
                .param("o", org.id()).param("c", ch.id()).param("d", d.id()).param("k", country).param("m", d.adMode()).param("vk",viewer.key()).param("aud",viewer.audiences()).param("until",viewer.until())
                .query(UUID.class).single();
        String url = edge.liveUrl(edgeSession(sid, r.org(), ch.slug(), country, d.adMode(), d.markerFormat(), d.kind()));
        Rights.Decision now = currentRights(org.id(), ch, country);
        return new Started(sid, url, d.adMode(), d.markerFormat(), d.name(), country, true, now);
    }

    /** The session JSON the Worker trusts once signed; ops.AvailVerifier builds its synthetic viewers with it too. */
    public String edgeSession(UUID sid, String org, String channel, String country, String adMode, String markers, String destKind) {
        return json.writeValueAsString(new EdgeSession(sid, org, channel, country, adMode, markers, destKind, System.currentTimeMillis()));
    }

    private Rights.Decision currentRights(UUID org, Channel ch, String country) {
        if (ch.publishedVersionId() == null) return null;
        Instant now = Instant.now();
        return data.entries(org, ch.publishedVersionId(), now, now.plusMillis(1)).stream().filter(e -> !e.isBreak()).findFirst()
                .map(e -> Rights.evaluate(data.policy(org, e.assetId()), ch.territoryList(), country, e.start(), e.end()))
                .orElse(null);
    }

    /**
     * Ad decision for one viewer and break, asked by the Worker ahead of the break (SSAI) or by the player's asset
     * list (SGAI). Ads are listed only up to the first creative not yet copied to the edge; slate covers the rest.
     */
    @GetMapping("/{session}/decisions/{breakId}")
    AdDecisionView decision(@PathVariable UUID session, @PathVariable UUID breakId,
                                        @RequestHeader(value = "X-Viewer-IP", defaultValue = "") String ip,
                                        @RequestHeader(value = "X-Viewer-UA", defaultValue = "") String ua) {
        var s = playout.session(session);
        AdDecisionServiceV1.Decision d = playout.decision(s, playout.breakEntry(s, breakId), new IAdSource.Viewer(ip, ua));
        List<AdDecisionServiceV1.Slot> ready = new ArrayList<>();
        for (var slot : d.slots()) {
            if (!publisher.stored(slot.mediaKey())) break;
            ready.add(slot);
        }
        if (ready.size() == d.slots().size()) return AdDecisionView.from(d);
        long filled = ready.stream().mapToLong(AdDecisionServiceV1.Slot::durationMs).sum();
        return AdDecisionView.from(new AdDecisionServiceV1.Decision(d.breakId(), d.breakMs(), ready, filled, d.excluded(), d.summary()));
    }

    public record Beacon(UUID breakId, UUID campaignId, @NotBlank String event) {}

    /** Permission can be withdrawn for future decisions; an already finalized break keeps its timeline. */
    @PostMapping("/{session}/privacy/contextual")
    ResponseEntity<Void> contextual(@PathVariable UUID session) {
        var s=playout.session(session);
        data.db.sql("update playback_session set viewer_key=null,audiences='',personalized_until=null where org_id=:o and id=:s")
                .param("o",s.orgId()).param("s",s.id()).update();
        return ResponseEntity.noContent().build();
    }

    /** Delivery beacons, from the player (SGAI) or the Worker (SSAI): accepted only for ads decided for this session and break. */
    @PostMapping("/{session}/beacons")
    ResponseEntity<Void> beacon(@PathVariable UUID session, @RequestBody Beacon b) {
        var s = playout.session(session);
        if (!Set.of("IMPRESSION", "FIRST_QUARTILE", "MIDPOINT", "THIRD_QUARTILE", "COMPLETE").contains(b.event()))
            throw ApiException.bad("Unknown event");
        AdDecisionServiceV1.Decision d = playout.decision(s, playout.breakEntry(s, b.breakId()));
        var slot = d.slots().stream().filter(x -> x.campaignId().equals(b.campaignId())).findFirst()
                .orElseThrow(() -> ApiException.bad("That ad wasn't served in this break"));
        if (playout.recordEvent(s, b.breakId(), slot.campaignId(), b.event(), (int) (slot.durationMs() / 1000)) == 1)
            vast.track(slot, b.event());   // external ads: tell the ad server, once
        return ResponseEntity.accepted().build();
    }

    // ------------------------------------------------------------------ what's on (for the player overlay)

    public record BreakInfo(UUID id, Instant start, Instant end, String ownership, boolean filledByUs, String scteOut,
                            AdDecisionView decision) {}

    public record EventRow(String event, String campaign, int seconds, java.time.OffsetDateTime occurredAt) {}

    public record Info(String channel, String channelStatus, String destination, String adMode, String markerFormat, String country,
                       Programme now, Programme next, BreakInfo currentBreak,
                       List<BreakInfo> upcomingBreaks, Rights.Decision rightsNow, List<EventRow> events, Instant serverTime) {}

    @GetMapping("/{session}/info")
    Info info(@PathVariable UUID session) {
        var s = playout.session(session);
        Channel ch = data.channel(s.orgId(), s.channelId());
        Instant now = Instant.now();
        var guide = epg.guide(s.orgId(), ch, now.minusSeconds(60), now.plusSeconds(6 * 3600));
        var cur = guide.stream().filter(Programme::now).findFirst().orElse(null);
        var next = guide.stream().filter(p -> p.start().isAfter(now)).findFirst().orElse(null);
        List<Entry> breaks = ch.publishedVersionId() == null ? List.of()
                : data.entries(s.orgId(), ch.publishedVersionId(), now, now.plusSeconds(3 * 3600)).stream().filter(Entry::isBreak).toList();
        BreakInfo curBreak = null;
        List<BreakInfo> upcoming = new ArrayList<>();
        for (Entry b : breaks) {
            boolean ours = PlayoutServiceV1.ours(s, b) && ch.territoryList().contains(s.country());
            boolean active = !b.start().isAfter(now);
            AdDecisionServiceV1.Decision d = ours && !"PASSTHROUGH".equals(s.adMode()) && active ? playout.decision(s, b) : null;
            BreakInfo bi = new BreakInfo(b.id(), b.start(), b.end(), b.ownership(), ours && !"PASSTHROUGH".equals(s.adMode()),
                    PlayoutServiceV1.scteHex(b, true), AdDecisionView.from(d));
            if (active) curBreak = bi;
            else if (upcoming.size() < 3) upcoming.add(bi);
        }
        List<EventRow> events = data.db.sql("""
                select e.event, coalesce(c.name, ca.title, 'Slate (unfilled)') as campaign, e.seconds, e.occurred_at
                from ad_event e left join campaign c on c.org_id = e.org_id and c.id = e.campaign_id
                left join asset ca on ca.org_id = e.org_id and ca.id = e.campaign_id
                where e.org_id = :o and e.session_id = :s order by e.id desc limit 12""")
                .param("o", s.orgId()).param("s", s.id()).query(EventRow.class).list();
        return new Info(ch.name(), ch.status(), s.destName(), s.adMode(), s.markerFormat(), s.country(), cur, next, curBreak, upcoming,
                currentRights(s.orgId(), ch, s.country()), events, now);
    }
}
