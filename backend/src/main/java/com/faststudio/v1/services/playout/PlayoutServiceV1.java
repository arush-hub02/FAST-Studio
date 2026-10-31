package com.faststudio.v1.services.playout;

import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.playout.PlaybackDto.Session;
import com.faststudio.v1.repositories.playout.PlaybackRepository;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control-plane side of playout. The live playlists themselves are built by the Cloudflare Worker (worker/) from
 * the schedule snapshot that edge.EdgePublisher publishes; this service keeps what needs the database: viewer
 * sessions, ad decisions (asked once per viewer and break), delivery events and SCTE-35 payloads.
 * Walkthrough in docs/HOW_FAST_WORKS.md §2–4.
 */
@Service
public class PlayoutServiceV1 {
    private final Data data;
    private final PlaybackRepository repository;
    private final AdDecisionServiceV1 ads;
    private final MediaSettingsServiceV1 settings;
    private final Set<String> slateRecorded = ConcurrentHashMap.newKeySet();

    public PlayoutServiceV1(Data data, PlaybackRepository repository, AdDecisionServiceV1 ads, MediaSettingsServiceV1 settings) {
        this.data = data;
        this.repository = repository;
        this.ads = ads;
        this.settings = settings;
    }

    /** Looks the viewer session up by its unguessable id (system lookup), then scopes the request to its tenant. */
    public Session session(UUID id) {
        Session s = TenantScope.bypass(() -> repository.session(id)).orElseThrow(() -> ApiException.notFound("Playback session"));
        TenantScope.set(s.orgId());
        return s;
    }

    /** True when this destination's viewer gets our ads in this break (vs. the platform filling it). */
    public static boolean ours(Session s, Entry brk) {
        return "OWNED_WEB".equals(s.destKind()) || "PROVIDER".equals(brk.ownership());
    }

    /** Returns 1 when recorded, 0 for a repeat (one event of each type per ad, break and session). */
    public int recordEvent(Session s, UUID breakId, UUID campaignId, String event, int seconds) {
        return repository.insertAdEvent(s.orgId(), s.id(), s.channelId(), breakId, campaignId, event, seconds);
    }

    /** time_signal + segmentation descriptor; provider vs distributor placement opportunity follows the pod's owner. */
    public static String scteHex(Entry brk, boolean out) {
        boolean provider = !"DISTRIBUTOR".equals(brk.ownership());
        int type = out ? (provider ? Scte35.PROVIDER_PO_START : Scte35.DISTRIBUTOR_PO_START)
                : (provider ? Scte35.PROVIDER_PO_END : Scte35.DISTRIBUTOR_PO_END);
        Instant at = out ? brk.start() : brk.end();
        long eventId = brk.id().getMostSignificantBits() >>> 32;
        return Scte35.hex(Scte35.timeSignal(Scte35.ticks(at.toEpochMilli()), eventId, type, out ? brk.durationMs() * 90 : null));
    }

    public Entry breakEntry(Session s, UUID breakId) {
        Channel ch = data.channel(s.orgId(), s.channelId());
        return repository.publishedBreak(s.orgId(), s.channelId(), breakId)
                .filter(e -> ch.publishedVersionId() != null)
                .orElseThrow(() -> ApiException.notFound("Ad break"));
    }

    public AdDecisionServiceV1.Decision decision(Session s, Entry brk) {
        return decision(s, brk, IAdSource.Viewer.NONE);
    }

    public AdDecisionServiceV1.Decision decision(Session s, Entry brk, IAdSource.Viewer viewer) {
        boolean enabled = settings.personalizedAds(s.orgId());   // dynamic property since V15
        boolean personalized = enabled && s.personalizedUntil()!=null && s.personalizedUntil().toInstant().isAfter(Instant.now());
        viewer = new IAdSource.Viewer(viewer.ip(),viewer.userAgent(),personalized?s.viewerKey():null,personalized?Times.codes(s.audiences()):List.of());
        AdDecisionServiceV1.Decision d = ads.decide(s.orgId(), s.id(), brk.id(), brk.start(), brk.durationMs(), brk.assetId(), s.country(), viewer);
        if (d.filledMs() < d.breakMs() && slateRecorded.add(s.id() + ":" + brk.id()))
            recordEvent(s, brk.id(), null, "SLATE", (int) ((d.breakMs() - d.filledMs()) / 1000));
        return d;
    }
}
