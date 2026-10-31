package com.faststudio.v1.services.ops;

import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;

import com.faststudio.v1.services.playout.PlaybackServiceV1;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.ops.MonitoringRepository;
import com.faststudio.v1.repositories.ops.MonitoringRepository.LiveChannel;
import com.faststudio.v1.repositories.playout.PlaybackRepository;
import com.faststudio.v1.repositories.playout.PlaybackRepository.Destination;
import com.faststudio.v1.util.cloudflare.EdgeClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards against the silent failure (handover §5.3, §14.4): the stream plays fine but its ad breaks carry no usable
 * markers, so nothing fills them and revenue is zero. For each LIVE channel and each marker format its destinations
 * receive, a synthetic viewer reads the edge's live playlist while a break airs; {@link #check} judges the markers once
 * the whole break has been seen, and {@link AlertScanner} raises an alert while the latest check of a format failed.
 */
@Component
public class AvailVerifier {
    private static final Logger log = LoggerFactory.getLogger(AvailVerifier.class);
    static final long WINDOW_MS = 12 * Times.SEG_MS;   // length of a live playlist (WINDOW in worker/src/playout.ts)
    static final String INTERSTITIAL = "com.apple.hls.interstitial";

    private final Data data;
    private final MonitoringRepository monitoring;
    private final PlaybackRepository destinations;
    private final EdgeClient edge;
    private final PlaybackServiceV1 playback;
    private final Map<UUID, Map<String, Result>> results = new ConcurrentHashMap<>();
    private final Map<String, Watch> watching = new HashMap<>();   // scheduler thread only

    public AvailVerifier(Data data, MonitoringRepository monitoring, PlaybackRepository destinations, EdgeClient edge, PlaybackServiceV1 playback) {
        this.data = data;
        this.monitoring = monitoring;
        this.destinations = destinations;
        this.edge = edge;
        this.playback = playback;
    }

    /** Latest finished check of one break, per format viewers receive: CUE_OUT, DATERANGE or INTERSTITIAL (SGAI). */
    public record Result(String markers, String destination, UUID breakId, Instant breakStart, Instant breakEnd,
                         boolean passed, List<String> problems, Instant checkedAt) {}

    public List<Result> results(UUID channel) { return List.copyOf(results.getOrDefault(channel, Map.of()).values()); }

    static final class Watch {
        final UUID breakId;
        final String session;
        final List<String> playlists = new ArrayList<>();
        boolean done;

        Watch(UUID breakId, String session) {
            this.breakId = breakId;
            this.session = session;
        }
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 20_000)   // well under WINDOW_MS, so consecutive playlists overlap
    public void tick() {
        if (!edge.configured()) return;   // EdgePublisher warns about that
        List<LiveChannel> rows = TenantScope.bypass(monitoring::liveChannels);
        for (LiveChannel r : rows) {
            try {
                if (!TenantScope.as(r.orgId(), () -> verify(r, Instant.now()))) return;   // edge unreachable: EdgePublisher reports it
            } catch (RuntimeException e) {
                log.warn("Avail check of channel {} failed: {}", r.channelId(), e.toString());
            }
        }
    }

    /** One fetch per format for the break on air (if any); false when the edge can't be reached. */
    boolean verify(LiveChannel r, Instant now) {
        Channel ch = data.channel(r.orgId(), r.channelId());
        if (ch.publishedVersionId() == null || ch.territoryList().isEmpty()) return true;
        Instant settled = ch.updatedAt().toInstant().plusSeconds(60);   // until then the edge may still serve the previous snapshot
        // breaks whose first segment has entered the live playlist and whose end hasn't left it yet
        List<Entry> airing = data.entries(r.orgId(), ch.publishedVersionId(), now.minusMillis(WINDOW_MS), now).stream()
                .filter(e -> e.isBreak() && !e.start().isBefore(settled) && now.isAfter(e.start().plusMillis(Times.SEG_MS))).toList();
        Map<String, Destination> probes = new LinkedHashMap<>();
        destinations.destinations(r.orgId())
                .forEach(d -> probes.putIfAbsent("SGAI".equals(d.adMode()) ? "INTERSTITIAL" : d.markerFormat(), d));
        Map<String, Result> latest = results.computeIfAbsent(ch.id(), k -> new ConcurrentHashMap<>());
        latest.keySet().retainAll(probes.keySet());
        for (var p : probes.entrySet()) {
            String markers = p.getKey(), key = ch.id() + "|" + markers;
            Destination d = p.getValue();
            Watch w = watching.get(key);
            Entry b = airing.stream().filter(e -> expected(d, e) && !(w != null && w.done && w.breakId.equals(e.id()))).findFirst().orElse(null);
            if (b == null) continue;
            Watch watch = w != null && w.breakId.equals(b.id()) ? w
                    // never stored, so it counts in no analytics; PASSTHROUGH gets SSAI's markers without the Worker asking for ads
                    : new Watch(b.id(), playback.edgeSession(UUID.randomUUID(), r.orgSlug(), ch.slug(), ch.territoryList().getFirst(),
                            "SGAI".equals(d.adMode()) ? "SGAI" : "PASSTHROUGH", d.markerFormat(), d.kind()));
            watching.put(key, watch);
            try {
                watch.playlists.add(edge.livePlaylist(watch.session));
            } catch (ConnectException | HttpTimeoutException e) {
                return false;
            } catch (IOException e) {
                log.debug("Avail check of {} skipped: {}", key, e.getMessage());   // a gap: this break ends up unjudged
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            Check c = check(watch.playlists, markers, b.start(), b.end());
            if (c.complete() || !c.problems().isEmpty()) {
                watch.done = true;
                watch.playlists.clear();
                latest.put(markers, new Result(markers, d.name(), b.id(), b.start(), b.end(), c.problems().isEmpty(), c.problems(), now));
                if (!c.problems().isEmpty()) log.warn("Avail check failed on {} for {}: {}", ch.name(), d.name(), c.problems());
            }
        }
        return true;
    }

    /** SGAI players only get interstitials for breaks we fill (ours() in worker/src/playout.ts); cue markers go out for every break. */
    static boolean expected(Destination d, Entry b) {
        return !"SGAI".equals(d.adMode()) || "OWNED_WEB".equals(d.kind()) || "PROVIDER".equals(b.ownership());
    }

    // ------------------------------------------------------------------ the marker rules

    /** complete = the playlists covered the break gap-free, from its first segment to the one after it. */
    public record Check(boolean complete, List<String> problems) {}

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    /**
     * Pure function of live playlists (oldest first) fetched while the break [start, end) aired. A playlist shows only
     * 60 s, so a long break is seen whole only across several; segments are merged by program date-time. Rules: a marker
     * of the expected format (CUE_OUT, DATERANGE or INTERSTITIAL) opens the break at its start; every CUE-OUT is closed
     * by a CUE-IN, and every DATERANGE SCTE35-OUT by an SCTE35-IN with its ID, once its announced duration has aired;
     * every marker carries a duration; no avail is zero-length.
     */
    static Check check(List<String> playlists, String markers, Instant start, Instant end) {
        record Seg(long end, List<String> cues) {}
        TreeMap<Long, Seg> segs = new TreeMap<>();                        // segment start (epoch ms) -> CUE tags before it
        Map<String, Map<String, String>> ranges = new LinkedHashMap<>();  // DATERANGE "ID|OUT", "ID|IN", "ID|AD" -> attributes
        for (String p : playlists) {
            Long t = null;
            List<String> cues = new ArrayList<>();
            for (String line : p.split("\n")) {
                line = line.strip();
                if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) t = Instant.parse(line.substring(25)).toEpochMilli();
                else if (line.startsWith("#EXT-X-CUE-")) cues.add(line);
                else if (line.startsWith("#EXT-X-DATERANGE:")) {
                    Map<String, String> a = attrs(line);
                    String kind = a.containsKey("SCTE35-IN") ? "IN" : a.containsKey("SCTE35-OUT") ? "OUT" : INTERSTITIAL.equals(a.get("CLASS")) ? "AD" : null;
                    if (kind != null) ranges.putIfAbsent(a.get("ID") + "|" + kind, a);
                } else if (line.startsWith("#EXTINF:") && t != null) {
                    long next = t + ms(line.substring(8).split(",")[0]);
                    segs.putIfAbsent(t, new Seg(next, cues));
                    cues = new ArrayList<>();
                    t = next;
                }
            }
        }
        Set<String> problems = new LinkedHashSet<>(), opened = new HashSet<>();
        if (segs.isEmpty()) return new Check(false, List.of());
        long s = start.toEpochMilli(), e = end.toEpochMilli(), from = segs.firstKey(), to = from;
        for (var x : segs.entrySet()) {
            if (x.getKey() != to) return new Check(false, List.of());   // a missed fetch: this break stays unjudged
            to = x.getValue().end();
        }

        Long outAt = null, outEnd = null;   // the open CUE-OUT avail
        for (var x : segs.entrySet()) {
            long t = x.getKey();
            List<String> cues = x.getValue().cues();
            if (outEnd != null && t >= outEnd && !cues.contains("#EXT-X-CUE-IN")) {
                problems.add("No CUE-IN where the avail from " + at(outAt) + " should end (" + at(outEnd) + ")");
                outAt = outEnd = null;
            }
            for (String c : cues) {
                Map<String, String> a = attrs(c);
                if (c.equals("#EXT-X-CUE-IN")) {
                    if (outAt == null && t != from) problems.add("CUE-IN at " + at(t) + " without a CUE-OUT");
                    if (outAt != null && outAt == t) problems.add("Zero-length avail at " + at(t) + ": CUE-OUT and CUE-IN on one segment");
                    outAt = outEnd = null;
                } else if (c.startsWith("#EXT-X-CUE-OUT-CONT")) {
                    Long d = duration(a.get("DURATION"), "CUE-OUT-CONT", problems), el = a.containsKey("ELAPSEDTIME") ? ms(a.get("ELAPSEDTIME")) : null;
                    if (outAt == null && t == from && d != null && el != null) {   // playlists start mid-avail
                        outAt = t - el;
                        outEnd = outAt + d;
                    } else if (outAt == null) problems.add("CUE-OUT-CONT without a CUE-OUT");
                } else if (c.startsWith("#EXT-X-CUE-OUT")) {
                    if (outAt != null) problems.add("CUE-OUT at " + at(t) + " while the avail from " + at(outAt) + " is still open");
                    Long d = duration(a.get("DURATION"), "CUE-OUT at " + at(t), problems);
                    outAt = t;
                    outEnd = d == null ? null : t + d;
                    if (t == s) opened.add("CUE_OUT");
                }
            }
        }

        for (var r : ranges.entrySet()) {
            Map<String, String> a = r.getValue();
            String id = a.get("ID"), kind = r.getKey().substring(r.getKey().lastIndexOf('|') + 1);
            String what = kind.equals("AD") ? "Interstitial " + id : "DATERANGE " + id + " SCTE35-" + kind;
            if (a.get("START-DATE") == null) {
                problems.add(what + " has no START-DATE");
                continue;
            }
            long t = Instant.parse(a.get("START-DATE")).toEpochMilli();
            Long d = duration(a.getOrDefault("DURATION", a.get("PLANNED-DURATION")), what, problems);
            if (t == s && !kind.equals("IN")) opened.add(kind.equals("AD") ? "INTERSTITIAL" : "DATERANGE");
            if (kind.equals("OUT") && d != null && to > t + d && !ranges.containsKey(id + "|IN"))
                problems.add(what + " from " + at(t) + " has no SCTE35-IN");
            if (kind.equals("IN") && t >= from && !ranges.containsKey(id + "|OUT"))
                problems.add(what + " has no SCTE35-OUT");
        }
        if (from <= s && s < to && !opened.contains(markers)) problems.add("No " + markers + " marker at the break's start (" + at(s) + ")");
        return new Check(from <= s && e < to, List.copyOf(problems));
    }

    /** A marker's duration in ms, or null after noting why it can't be used. */
    private static Long duration(String v, String what, Set<String> problems) {
        Long d = v == null ? null : ms(v);
        if (d == null) problems.add(what + " carries no duration");
        else if (d <= 0) problems.add(what + " is a zero-length avail");
        return d == null || d <= 0 ? null : d;
    }

    private static Long ms(String seconds) {
        try {
            return Math.round(Double.parseDouble(seconds) * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> attrs(String tag) {
        Map<String, String> a = new HashMap<>();
        Matcher m = ATTR.matcher(tag.substring(tag.indexOf(':') + 1));
        while (m.find()) a.put(m.group(1).toUpperCase(Locale.ROOT), m.group(2).replace("\"", ""));
        return a;
    }

    private static String at(long t) { return Instant.ofEpochMilli(t).toString(); }
}
