package com.faststudio.playout;

import com.faststudio.ads.AdDecisionService;
import com.faststudio.core.ApiException;
import com.faststudio.core.Data;
import com.faststudio.core.Data.Asset;
import com.faststudio.core.Data.Channel;
import com.faststudio.core.Data.Entry;
import com.faststudio.core.TenantScope;
import com.faststudio.core.Times;
import com.faststudio.media.MediaService;
import com.faststudio.media.MediaSigner;
import com.faststudio.rights.Rights;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.faststudio.core.Times.EPOCH;
import static com.faststudio.core.Times.SEG_MS;

/**
 * Virtual-linear playout: turns the published schedule + wall clock into live HLS playlists — a master playlist
 * listing the quality ladder and one media playlist per rendition (identical timing, so players switch freely).
 * Segment i always covers [EPOCH + 3s*i, +3s), so EXT-X-MEDIA-SEQUENCE is simply i — deterministic across requests.
 * Ad breaks get SCTE-35 markers; per destination they are passed through, stitched (SSAI) or turned into
 * HLS Interstitials (SGAI). Walkthrough in docs/HOW_FAST_WORKS.md §2–4.
 */
@Service
public class PlayoutService {
    public static final int WINDOW = 20;           // 60 s live window
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Data data;
    private final MediaService media;
    private final AdDecisionService ads;
    private final MediaSigner signer;
    static final Duration MEDIA_TTL = Duration.ofHours(2);   // segment URL validity, counted from the segment's own air time
    // session -> {segment index, discontinuity sequence at that index}; keeps DISCONTINUITY-SEQUENCE monotonic and cheap
    private final Map<UUID, long[]> dsnMemo = new ConcurrentHashMap<>();
    private final Set<String> slateRecorded = ConcurrentHashMap.newKeySet();

    public PlayoutService(Data data, MediaService media, AdDecisionService ads, MediaSigner signer) {
        this.data = data;
        this.media = media;
        this.ads = ads;
        this.signer = signer;
    }

    public record Session(UUID id, UUID orgId, UUID channelId, UUID destinationId, String country, String adMode,
                          OffsetDateTime createdAt, String markerFormat, String destKind, String destName) {}

    /** Looks the viewer session up by its unguessable id (system lookup), then scopes the request to its tenant. */
    public Session session(UUID id) {
        Session s = TenantScope.bypass(() -> data.db.sql("""
                select s.id, s.org_id, s.channel_id, s.destination_id, s.country, s.ad_mode, s.created_at,
                       d.marker_format, d.kind as dest_kind, d.name as dest_name
                from playback_session s join destination d on d.org_id = s.org_id and d.id = s.destination_id where s.id = :id""")
                .param("id", id).query(Session.class).optional()).orElseThrow(() -> ApiException.notFound("Playback session"));
        TenantScope.set(s.orgId());
        return s;
    }

    static Instant time(long i) { return EPOCH.plusMillis(i * SEG_MS); }

    static long index(Instant t) { return Math.floorDiv(t.toEpochMilli() - EPOCH.toEpochMilli(), SEG_MS); }

    /** What plays in one segment slot: key changes => EXT-X-DISCONTINUITY. The URI is rendered per rendition. */
    record Seg(String key, String mediaKey, long n, String adBase) {
        static Seg media(String key, String mediaKey, long n) { return new Seg(key, mediaKey, n, null); }
    }

    String uri(Seg g, int variant, Instant airTime) {
        if (g.adBase() != null) return g.adBase() + variant + "/" + g.n() + ".ts";
        return signer.url(g.mediaKey(), media.variantPath(g.mediaKey(), variant) + segFile(g.n()), airTime.plus(MEDIA_TTL));
    }

    /** True when this destination's viewer gets our ads in this break (vs. the platform filling it). */
    static boolean ours(Session s, Entry brk) {
        return "OWNED_WEB".equals(s.destKind()) || "PROVIDER".equals(brk.ownership());
    }

    final class Resolver {
        final Session s;
        final Channel ch;
        final boolean live, offered;   // offered = channel is available in the viewer's country
        final List<Entry> entries;
        final Map<UUID, Asset> assets;
        final Map<UUID, Rights.Policy> policies;
        final Map<UUID, Boolean> allowed = new HashMap<>();
        final int standbyN = Math.max(1, media.segments(MediaService.STANDBY)), geoN = Math.max(1, media.segments(MediaService.GEO));

        Resolver(Session s, Instant from, Instant to) {
            this.s = s;
            this.ch = data.channel(s.orgId(), s.channelId());
            this.live = "LIVE".equals(ch.status()) && ch.publishedVersionId() != null;
            this.offered = ch.territoryList().contains(s.country());
            this.entries = live ? data.entries(s.orgId(), ch.publishedVersionId(), from, to) : List.of();
            this.assets = data.assets(s.orgId());
            this.policies = data.policies(s.orgId());
        }

        Entry at(Instant t) {
            int lo = 0, hi = entries.size() - 1, best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (!entries.get(mid).start().isAfter(t)) { best = mid; lo = mid + 1; } else hi = mid - 1;
            }
            return best >= 0 && entries.get(best).end().isAfter(t) ? entries.get(best) : null;
        }

        boolean rightsOk(UUID assetId, Instant from, Instant to, Object cacheKey) {
            return allowed.computeIfAbsent(cacheKey instanceof UUID u ? u : UUID.nameUUIDFromBytes(cacheKey.toString().getBytes()),
                    k -> Rights.evaluate(policies.get(assetId), ch.territoryList(), s.country(), from, to).allowed());
        }

        Seg standby(long i) {
            return Seg.media("sb:" + Math.floorDiv(i, standbyN), MediaService.STANDBY, Math.floorMod(i, standbyN));
        }

        Seg geo(long i) {
            return Seg.media("geo:" + Math.floorDiv(i, geoN), MediaService.GEO, Math.floorMod(i, geoN));
        }

        Seg seg(long i) {
            Instant t = time(i);
            if (!live) return standby(i);
            if (!offered) return geo(i);   // channel not offered here: no programmes, no ads, no markers
            Entry e = at(t);
            if (e == null) return fallback(i, t);
            if (!e.isBreak()) {
                Asset a = assets.get(e.assetId());
                if (a == null || !a.ready() || a.mediaKey() == null) return standby(i);
                if (!rightsOk(a.id(), e.start(), e.end(), e.id())) return geo(i);
                long n = (e.assetOffsetMs() + (t.toEpochMilli() - e.start().toEpochMilli())) / SEG_MS;
                if (n >= media.segments(a.mediaKey())) return standby(i);
                return Seg.media("e:" + e.id(), a.mediaKey(), n);
            }
            if ("SSAI".equals(s.adMode()) && ours(s, e)) {
                AdDecisionService.Decision d = decide(e);
                long off = t.toEpochMilli() - e.start().toEpochMilli();
                for (int k = 0; k < d.slots().size(); k++) {
                    var slot = d.slots().get(k);
                    if (off < slot.durationMs())
                        return new Seg("ad:" + e.id() + ":" + k, null, off / SEG_MS, "/play/" + s.id() + "/ad/" + e.id() + "/" + k + "/");
                    off -= slot.durationMs();
                }
            }
            return standby(i);
        }

        Seg fallback(long i, Instant t) {
            Asset fb = ch.fallbackAssetId() == null ? null : assets.get(ch.fallbackAssetId());
            if (fb == null || !fb.ready() || fb.mediaKey() == null || fb.durationMs() < SEG_MS
                    || !rightsOk(fb.id(), t, t.plusMillis(SEG_MS), "fb:" + fb.id())) return standby(i);
            long pos = i * SEG_MS;
            long n = Math.floorMod(pos, fb.durationMs()) / SEG_MS;
            return Seg.media("fb:" + Math.floorDiv(pos, fb.durationMs()), fb.mediaKey(), n);
        }

        AdDecisionService.Decision decide(Entry e) {
            AdDecisionService.Decision d = ads.decide(s.orgId(), s.id(), e.id(), e.start(), e.durationMs(), e.assetId(), s.country());
            if (d.filledMs() < d.breakMs() && slateRecorded.add(s.id() + ":" + e.id()))
                recordEvent(s, e.id(), null, "SLATE", (int) ((d.breakMs() - d.filledMs()) / 1000));
            return d;
        }
    }

    static String segFile(long n) { return String.format("seg_%05d.ts", n); }

    public void recordEvent(Session s, UUID breakId, UUID campaignId, String event, int seconds) {
        data.db.sql("""
                insert into ad_event(org_id, session_id, channel_id, break_id, campaign_id, event, seconds)
                values (:o,:s,:c,:b,:camp,:e,:sec) on conflict do nothing""")
                .param("o", s.orgId()).param("s", s.id()).param("c", s.channelId()).param("b", breakId)
                .param("camp", campaignId).param("e", event).param("sec", seconds).update();
    }

    // ------------------------------------------------------------------ the live playlists

    /** Master playlist: the quality ladder, each entry pointing at that rendition's live media playlist. */
    public String master(UUID sessionId) {
        Session s = session(sessionId);
        return MediaService.masterPlaylist(v -> "/play/" + s.id() + "/v/" + v + ".m3u8");
    }

    public String playlist(UUID sessionId, int variant, Instant now) {
        Session s = session(sessionId);
        long edge = index(now) - 1;                      // newest segment that has fully "aired"
        long first = edge - WINDOW + 1;
        long origin = index(s.createdAt().toInstant()) - WINDOW;
        long[] memo = dsnMemo.get(s.id());
        long startIdx = memo != null && memo[0] <= first && memo[0] >= origin ? memo[0] : Math.min(origin, first);
        long dsn = memo != null && startIdx == memo[0] ? memo[1] : 0;

        Resolver r = new Resolver(s, time(startIdx).minusSeconds(1), time(edge + 2).plusSeconds(SGAI_LOOKAHEAD_S));
        String prevKey = r.seg(startIdx).key();
        for (long i = startIdx + 1; i <= first; i++) {
            String k = r.seg(i).key();
            if (!k.equals(prevKey)) dsn++;
            prevKey = k;
        }
        dsnMemo.put(s.id(), new long[]{first, dsn});

        StringBuilder m = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        m.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');
        m.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(dsn).append('\n');
        m.append("## FAST Studio prototype playout | channel=").append(r.ch.slug()).append(" | destination=").append(s.destName())
                .append(" | ad mode=").append(s.adMode()).append(" | markers=").append(s.markerFormat())
                .append(" | simulated viewer country=").append(s.country()).append('\n');
        Seg prev = null;
        Set<UUID> announced = new HashSet<>();
        // SGAI: announce upcoming breaks ahead of the live edge so the player can fetch the ad list
        // before it reaches the break (players stall if an interstitial first appears at its own start).
        if (r.live && r.offered && "SGAI".equals(s.adMode())) {
            Instant from = time(edge + 1), until = from.plusSeconds(SGAI_LOOKAHEAD_S);
            for (Entry e : r.entries)
                if (e.isBreak() && ours(s, e) && !e.start().isBefore(from) && e.start().isBefore(until) && announced.add(e.id()))
                    m.append(interstitial(s, e));
        }
        for (long i = first; i <= edge; i++) {
            Instant t = time(i);
            Seg g = r.seg(i);
            boolean disc = prev != null && !g.key().equals(prev.key());
            if (disc) m.append("#EXT-X-DISCONTINUITY\n");
            if (prev == null || disc) m.append("#EXT-X-PROGRAM-DATE-TIME:").append(ISO.format(t)).append('\n');
            if (r.live && r.offered) markers(m, s, r, i == first, t, announced);
            m.append("#EXTINF:3.000,\n").append(uri(g, variant, t)).append('\n');
            prev = g;
        }
        return m.toString();
    }

    private void markers(StringBuilder m, Session s, Resolver r, boolean firstInWindow, Instant t, Set<UUID> announced) {
        Entry cur = r.at(t);
        if (cur != null && !cur.isBreak()) cur = null;
        Entry before = r.at(t.minusMillis(SEG_MS));
        if (before != null && (!before.isBreak() || !before.end().equals(t))) before = null;
        boolean ended = before != null && (cur == null || !cur.id().equals(before.id()));

        if ("SGAI".equals(s.adMode())) {
            if (cur != null && ours(s, cur) && (t.equals(cur.start()) || firstInWindow) && announced.add(cur.id()))
                m.append(interstitial(s, cur));
            return;
        }
        if ("CUE_OUT".equals(s.markerFormat())) {
            if (ended) m.append("#EXT-X-CUE-IN\n");
            if (cur != null) {
                double d = cur.durationMs() / 1000.0;
                if (t.equals(cur.start())) m.append(String.format(Locale.ROOT, "#EXT-X-CUE-OUT:DURATION=%.3f\n", d));
                else m.append(String.format(Locale.ROOT, "#EXT-X-CUE-OUT-CONT:ElapsedTime=%.3f,Duration=%.3f\n",
                        (t.toEpochMilli() - cur.start().toEpochMilli()) / 1000.0, d));
            }
            return;
        }
        if (ended) m.append(String.format(Locale.ROOT, "#EXT-X-DATERANGE:ID=\"%s\",START-DATE=\"%s\",DURATION=%.3f,SCTE35-IN=%s\n",
                before.id(), ISO.format(before.start()), before.durationMs() / 1000.0, scteHex(before, false)));
        if (cur != null && (t.equals(cur.start()) || firstInWindow) && announced.add(cur.id()))
            m.append(String.format(Locale.ROOT, "#EXT-X-DATERANGE:ID=\"%s\",START-DATE=\"%s\",PLANNED-DURATION=%.3f,SCTE35-OUT=%s\n",
                    cur.id(), ISO.format(cur.start()), cur.durationMs() / 1000.0, scteHex(cur, true)));
    }

    static final int SGAI_LOOKAHEAD_S = 30;

    /** HLS Interstitial (SGAI): a pointer to the ad list; X-RESUME-OFFSET keeps the viewer on the live timeline. */
    static String interstitial(Session s, Entry brk) {
        double d = brk.durationMs() / 1000.0;
        return String.format(Locale.ROOT,
                "#EXT-X-DATERANGE:ID=\"ad-%s\",CLASS=\"com.apple.hls.interstitial\",START-DATE=\"%s\",DURATION=%.3f,X-ASSET-LIST=\"/play/%s/asset-list/%s.json\",X-RESUME-OFFSET=%.3f,X-RESTRICT=\"SKIP,JUMP\"\n",
                brk.id(), ISO.format(brk.start()), d, s.id(), brk.id(), d);
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

    // ------------------------------------------------------------------ SSAI segments + SGAI asset lists

    public Entry breakEntry(Session s, UUID breakId) {
        Channel ch = data.channel(s.orgId(), s.channelId());
        return data.db.sql("select id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership from schedule_entry where org_id = :o and id = :id and kind = 'AD_BREAK'")
                .param("o", s.orgId()).param("id", breakId).query(Entry.class).optional()
                .filter(e -> ch.publishedVersionId() != null)
                .orElseThrow(() -> ApiException.notFound("Ad break"));
    }

    public AdDecisionService.Decision decision(Session s, Entry brk) {
        AdDecisionService.Decision d = ads.decide(s.orgId(), s.id(), brk.id(), brk.start(), brk.durationMs(), brk.assetId(), s.country());
        if (d.filledMs() < d.breakMs() && slateRecorded.add(s.id() + ":" + brk.id()))
            recordEvent(s, brk.id(), null, "SLATE", (int) ((d.breakMs() - d.filledMs()) / 1000));
        return d;
    }

    /** Server-side beacons: an ad segment fetch is the evidence that the ad was delivered. */
    public String adSegment(Session s, UUID breakId, int slotIdx, int variant, int n) {
        AdDecisionService.Decision d = decision(s, breakEntry(s, breakId));
        if (slotIdx < 0 || slotIdx >= d.slots().size()) throw ApiException.notFound("Ad");
        var slot = d.slots().get(slotIdx);
        int segs = slot.segments();
        if (n < 0 || n >= segs) throw ApiException.notFound("Ad segment");
        String ev = n == 0 ? "IMPRESSION" : n == segs - 1 ? "COMPLETE" : n == segs / 4 ? "FIRST_QUARTILE"
                : n == segs / 2 ? "MIDPOINT" : n == (3 * segs) / 4 ? "THIRD_QUARTILE" : null;
        if (ev != null) recordEvent(s, breakId, slot.campaignId(), ev, (int) (slot.durationMs() / 1000));
        return slot.mediaKey() + "/" + media.variantPath(slot.mediaKey(), variant) + segFile(n);
    }

    public Map<String, Object> assetList(Session s, UUID breakId) {
        Entry brk = breakEntry(s, breakId);
        AdDecisionService.Decision d = decision(s, brk);
        List<Map<String, Object>> assets = new ArrayList<>();
        for (var slot : d.slots())
            assets.add(Map.of("URI", signer.url(slot.mediaKey(), media.entryPlaylist(slot.mediaKey())), "DURATION", slot.durationMs() / 1000.0,
                    "X-CAMPAIGN-ID", slot.campaignId().toString(), "X-CAMPAIGN", slot.campaign()));
        long rest = d.breakMs() - d.filledMs();
        if (rest >= SEG_MS)   // pad with slate so the viewer rejoins the live timeline exactly at break end
            assets.add(Map.of("URI", "/play/" + s.id() + "/slate/" + rest / 1000 + ".m3u8", "DURATION", rest / 1000.0));
        return Map.of("ASSETS", assets);
    }

    public String slatePlaylist(int seconds) {
        int n = Math.max(1, Math.min(seconds, 600) / 3), total = Math.max(1, media.segments(MediaService.STANDBY));
        Instant exp = Instant.now().plus(MEDIA_TTL);
        String dir = media.variantPath(MediaService.STANDBY, 0);
        StringBuilder m = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (int i = 0; i < n; i++) {
            if (i > 0 && i % total == 0) m.append("#EXT-X-DISCONTINUITY\n");
            m.append("#EXTINF:3.000,\n").append(signer.url(MediaService.STANDBY, dir + segFile(i % total), exp)).append('\n');
        }
        return m.append("#EXT-X-ENDLIST\n").toString();
    }
}
