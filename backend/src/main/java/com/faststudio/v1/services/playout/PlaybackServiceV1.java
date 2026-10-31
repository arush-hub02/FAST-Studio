package com.faststudio.v1.services.playout;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.dto.playout.PlaybackDto.*;
import com.faststudio.v1.dto.programming.ScheduleDto.Programme;
import com.faststudio.v1.repositories.playout.PlaybackRepository;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;
import com.faststudio.v1.services.ads.ViewerContextService;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.ads.sources.VastSource;
import com.faststudio.v1.services.programming.EpgServiceV1;
import com.faststudio.v1.services.rights.Rights;
import com.faststudio.v1.util.cloudflare.EdgeClient;
import com.faststudio.v1.util.cloudflare.EdgePublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/**
 * Public viewer-side playback (no admin session). Starting a session returns the Worker's live stream URL with a signed
 * session token; the Worker calls back only for ad decisions and delivery events, once per break.
 */
@Service
public class PlaybackServiceV1 {
    private static final Set<String> BEACON_EVENTS = Set.of("IMPRESSION", "FIRST_QUARTILE", "MIDPOINT", "THIRD_QUARTILE", "COMPLETE");

    private final Data data;
    private final PlaybackRepository repository;
    private final PlayoutServiceV1 playout;
    private final EpgServiceV1 epg;
    private final ViewerContextService viewers;
    private final VastSource vast;
    private final EdgeClient edge;
    private final EdgePublisher publisher;
    private final JsonMapper json;

    public PlaybackServiceV1(Data data, PlaybackRepository repository, PlayoutServiceV1 playout, EpgServiceV1 epg, ViewerContextService viewers,
                             VastSource vast, EdgeClient edge, EdgePublisher publisher, JsonMapper json) {
        this.data = data;
        this.repository = repository;
        this.playout = playout;
        this.epg = epg;
        this.viewers = viewers;
        this.vast = vast;
        this.edge = edge;
        this.publisher = publisher;
        this.json = json;
    }

    /** What the Worker needs to know about a viewer, signed so it can trust it without a database. */
    private record EdgeSession(UUID sid, String org, String channel, String country, String adMode, String markers, String destKind, long created) {}

    /** Starts one viewer session on a live channel. Unknown or suspended projects look like an unknown channel. */
    public Started start(StartRequest r) {
        var org = repository.organizationBySlug(r.org()).orElseThrow(() -> ApiException.notFound("Channel"));
        TenantScope.set(org.id());          // everything below is read under this tenant's row-level security
        if (!"ACTIVE".equals(org.status())) throw ApiException.notFound("Channel");
        ViewerContextService.Verified viewer = viewers.verify(org.id(), r.viewerToken());
        UUID chId = repository.channelBySlug(org.id(), r.channel()).orElseThrow(() -> ApiException.notFound("Channel"));
        Channel ch = data.channel(org.id(), chId);
        if (!"LIVE".equals(ch.status()))
            throw new ApiException(HttpStatus.CONFLICT, "NOT_LIVE", "\"" + ch.name() + "\" isn't on air. Publish a schedule and press 'Go live' first.");
        var d = repository.destination(org.id(), r.destinationId()).orElseThrow(() -> ApiException.notFound("Destination"));
        String country = r.country().toUpperCase(Locale.ROOT);
        UUID sid = repository.insertSession(org.id(), ch.id(), d.id(), country, d.adMode(), viewer.key(), viewer.audiences(), viewer.until());
        String url = edge.liveUrl(edgeSession(sid, r.org(), ch.slug(), country, d.adMode(), d.markerFormat(), d.kind()));
        return new Started(sid, url, d.adMode(), d.markerFormat(), d.name(), country, true, currentRights(org.id(), ch, country));
    }

    /** The session JSON the Worker trusts once signed; ops.AvailVerifier builds its synthetic viewers with it too. */
    public String edgeSession(UUID sid, String org, String channel, String country, String adMode, String markers, String destKind) {
        return json.writeValueAsString(new EdgeSession(sid, org, channel, country, adMode, markers, destKind, System.currentTimeMillis()));
    }

    /** Rights of the programme on air right now for the viewer's country (null when nothing is published). */
    private Rights.Decision currentRights(UUID org, Channel ch, String country) {
        if (ch.publishedVersionId() == null) return null;
        Instant now = Instant.now();
        return data.entries(org, ch.publishedVersionId(), now, now.plusMillis(1)).stream().filter(e -> !e.isBreak()).findFirst()
                .map(e -> Rights.evaluate(data.policy(org, e.assetId()), ch.territoryList(), country, e.start(), e.end()))
                .orElse(null);
    }

    /**
     * Ad decision for one viewer and break, asked by the Worker ahead of the break (SSAI) or by the player (SGAI).
     * Ads are listed only up to the first creative not yet copied to the edge; slate covers the rest.
     */
    public AdDecisionView decision(UUID session, UUID breakId, String ip, String userAgent) {
        var s = playout.session(session);
        AdDecisionServiceV1.Decision d = playout.decision(s, playout.breakEntry(s, breakId), new IAdSource.Viewer(ip, userAgent));
        List<AdDecisionServiceV1.Slot> ready = new ArrayList<>();
        for (var slot : d.slots()) {
            if (!publisher.stored(slot.mediaKey())) break;
            ready.add(slot);
        }
        if (ready.size() == d.slots().size()) return AdDecisionView.from(d);
        long filled = ready.stream().mapToLong(AdDecisionServiceV1.Slot::durationMs).sum();
        return AdDecisionView.from(new AdDecisionServiceV1.Decision(d.breakId(), d.breakMs(), ready, filled, d.excluded(), d.summary()));
    }

    /** Permission can be withdrawn for future decisions; an already finalized break keeps its timeline. */
    public void contextualOnly(UUID session) {
        var s = playout.session(session);
        repository.forgetViewer(s.orgId(), s.id());
    }

    /** Accepted only for ads decided for this session and break; external ad servers are told once per event. */
    public void beacon(UUID session, Beacon b) {
        var s = playout.session(session);
        if (!BEACON_EVENTS.contains(b.event())) throw ApiException.bad("Unknown event");
        AdDecisionServiceV1.Decision d = playout.decision(s, playout.breakEntry(s, b.breakId()));
        var slot = d.slots().stream().filter(x -> x.campaignId().equals(b.campaignId())).findFirst()
                .orElseThrow(() -> ApiException.bad("That ad wasn't served in this break"));
        if (playout.recordEvent(s, b.breakId(), slot.campaignId(), b.event(), (int) (slot.durationMs() / 1000)) == 1)
            vast.track(slot, b.event());
    }

    /** The player overlay: what's on, ad breaks around now and the session's recent delivery events. */
    public Info info(UUID session) {
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
        return new Info(ch.name(), ch.status(), s.destName(), s.adMode(), s.markerFormat(), s.country(), cur, next, curBreak, upcoming,
                currentRights(s.orgId(), ch, s.country()), repository.recentEvents(s.orgId(), s.id()), now);
    }
}
