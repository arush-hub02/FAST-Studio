package com.faststudio.v1.services.programming;

import com.faststudio.v1.core.common.Data.Asset;
import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;

import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.services.rights.Rights;


import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Pure scheduling rules: validation and auto-fill. No database access, unit-tested directly. */
public final class Planner {
    private Planner() {}

    public record Issue(String severity, String code, String message, UUID entryId, String hint, Instant at) {}

    /** A planned row before it is stored. */
    public record Item(String kind, UUID assetId, long offsetMs, Instant start, Instant end, String ownership) {}

    public record Plan(List<Item> items, List<String> notices, Instant end) {}

    static final long HOUR = 3_600_000L;
    static final long MAX_BREAK_MS = 300_000L;

    public static String fmt(Instant t, String tz) {
        return DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.of(tz)).format(t);
    }

    public static long hourOf(Instant t) { return Math.floorDiv(t.toEpochMilli(), HOUR); }

    // ------------------------------------------------------------------ validation

    public static List<Issue> validate(Channel ch, List<Entry> entries, Map<UUID, Asset> assets,
                                       Map<UUID, Rights.Policy> policies, Instant now) {
        List<Issue> out = new ArrayList<>();
        List<String> terr = ch.territoryList();
        String tz = ch.timezone();
        if (terr.isEmpty())
            out.add(new Issue("ERROR", "NO_TERRITORIES", "The channel isn't offered in any country yet.", null, "Open channel settings and pick at least one country.", null));
        Map<Long, Long> adPerHour = new TreeMap<>();
        Entry prev = null;
        boolean anyFuture = false;
        for (Entry e : entries) {
            boolean past = !e.end().isAfter(now);
            String when = fmt(e.start(), tz);
            if (!Times.aligned(e.start()) || !Times.aligned(e.end()))
                out.add(new Issue("ERROR", "MISALIGNED", "Item at " + when + " doesn't start on a " + Times.SEG_S + "-second boundary.", e.id(), "Remove and re-add it; the editor snaps times automatically.", e.start()));
            if (prev != null) {
                if (e.start().isBefore(prev.end()))
                    out.add(new Issue("ERROR", "OVERLAP", "Two items overlap at " + when + ". A channel can only play one thing at a time.", e.id(), "Delete one of them or use 'Close gaps' after removing it.", e.start()));
                else if (e.start().isAfter(prev.end()) && e.start().isAfter(now)) {
                    long gapMin = Math.max(1, (e.start().toEpochMilli() - prev.end().toEpochMilli()) / 60000);
                    out.add(new Issue("WARNING", "GAP", "There is a " + gapMin + "-minute gap before " + when + ". Viewers will see the standby screen during it.", e.id(), "Click 'Close gaps' to pull later items forward.", prev.end()));
                }
            }
            prev = e;
            if (past) continue;
            anyFuture = true;
            if (!e.isBreak()) {
                Asset a = assets.get(e.assetId());
                if (a == null) {
                    out.add(new Issue("ERROR", "MISSING_ASSET", "A scheduled video no longer exists (" + when + ").", e.id(), "Remove this item.", e.start()));
                    continue;
                }
                if (!a.ready())
                    out.add(new Issue("ERROR", "NOT_READY", "\"" + a.title() + "\" at " + when + " isn't ready to play yet (" + a.status().toLowerCase() + ").", e.id(), "Wait for processing to finish, or replace it.", e.start()));
                else if (e.assetOffsetMs() + e.durationMs() > a.durationMs())
                    out.add(new Issue("ERROR", "TOO_LONG", "\"" + a.title() + "\" at " + when + " is scheduled longer than the video itself.", e.id(), "Remove and re-add it.", e.start()));
                if (!terr.isEmpty()) {
                    List<String> blocked = new ArrayList<>();
                    String reason = "";
                    for (String c : terr) {
                        Rights.Decision d = Rights.evaluate(policies.get(a.id()), terr, c, e.start(), e.end());
                        if (!d.allowed()) { blocked.add(c); reason = d.summary(); }
                    }
                    if (blocked.size() == terr.size())
                        out.add(new Issue("ERROR", "RIGHTS", "\"" + a.title() + "\" at " + when + " can't play in any of this channel's countries. " + reason, e.id(), "Fix the licence on the Rights page, or remove this programme.", e.start()));
                    else if (!blocked.isEmpty())
                        out.add(new Issue("WARNING", "BLACKOUT", "\"" + a.title() + "\" at " + when + " isn't licensed in " + String.join(", ", blocked) + ". Viewers there will see a 'not available in your region' screen.", e.id(), "Acceptable if intended; otherwise extend the licence.", e.start()));
                }
            } else {
                if (e.durationMs() > MAX_BREAK_MS)
                    out.add(new Issue("ERROR", "BREAK_TOO_LONG", "Ad break at " + when + " is longer than 5 minutes.", e.id(), "Use shorter breaks.", e.start()));
                Rights.Policy p = e.assetId() == null ? null : policies.get(e.assetId());
                if (p != null && !p.adsAllowed()) {
                    Asset a = assets.get(e.assetId());
                    out.add(new Issue("ERROR", "ADS_NOT_ALLOWED", "The licence for \"" + (a == null ? "this programme" : a.title()) + "\" doesn't allow ads, but there is an ad break inside it at " + when + ".", e.id(), "Delete this ad break.", e.start()));
                }
                adPerHour.merge(hourOf(e.start()), e.durationMs(), Long::sum);
            }
        }
        long limit = ch.adSecondsPerHour() * 1000L;
        adPerHour.forEach((h, ms) -> {
            if (ms > limit) {
                Instant at = Instant.ofEpochMilli(h * HOUR);
                out.add(new Issue("ERROR", "AD_LOAD", "Too many ads in the hour starting " + fmt(at, tz) + ": " + ms / 1000 + " s of breaks, limit is " + ch.adSecondsPerHour() + " s per hour.", null, "Delete a break in that hour or raise the limit in channel settings.", at));
            }
        });
        if (!anyFuture)
            out.add(new Issue("ERROR", "EMPTY", "Nothing is scheduled from now on.", null, "Click 'Auto-fill' to build a schedule from your videos.", now));
        else if (prev != null && prev.end().isBefore(now.plusSeconds(6 * 3600)))
            out.add(new Issue("WARNING", "COVERAGE", "The schedule runs out at " + fmt(prev.end(), tz) + ". After that viewers see the standby screen.", null, "Auto-fill more hours before then.", prev.end()));
        out.sort(Comparator.comparing((Issue i) -> i.severity().equals("ERROR") ? 0 : 1)
                .thenComparing(i -> i.at() == null ? Instant.EPOCH : i.at()));
        return out;
    }

    // ------------------------------------------------------------------ auto-fill

    /**
     * Fills [from, from+hours) by rotating through eligible videos. Mid-roll breaks go on the video's cue points,
     * plus one break between programmes, while the per-hour ad limit allows. Ownership alternates
     * provider/distributor to model an inventory split (HOW_FAST_WORKS §4.4).
     */
    public static Plan autofill(Channel ch, List<Asset> pool, Map<UUID, Rights.Policy> policies, Instant from, int hours,
                                Map<Long, Long> existingAdPerHour) {
        List<Item> items = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        Map<Long, Long> adPerHour = new HashMap<>(existingAdPerHour);
        List<String> terr = ch.territoryList();
        Instant t = Times.snapUp(from), until = from.plusSeconds(hours * 3600L);
        List<Asset> candidates = pool.stream().filter(a -> a.ready() && a.durationMs() >= Times.SEG_MS
                && !a.kind().equals("AD") && !a.kind().equals("SLATE")).toList();
        if (candidates.isEmpty()) {
            notices.add("No ready videos to schedule. Add videos in the Library first.");
            return new Plan(items, notices, t);
        }
        int i = 0, skippedInRow = 0;
        boolean provider = true;
        Set<String> skippedTitles = new LinkedHashSet<>();
        while (t.isBefore(until)) {
            Asset a = candidates.get(i++ % candidates.size());
            Rights.Policy p = policies.get(a.id());
            boolean adsOk = p != null && p.adsAllowed();
            // no mid-rolls allowed => keep the programme in one piece
            List<Long> cues = !adsOk ? List.of() : a.cues().stream().map(Times::snapMs).filter(c -> c > 0 && c < a.durationMs()).distinct().toList();
            long slot = a.durationMs() + (adsOk ? cues.size() * ch.breakSeconds() * 1000L : 0);
            Instant slotEnd = t.plusMillis(slot);
            Instant start = t;
            boolean playable = terr.stream().anyMatch(c -> Rights.evaluate(p, terr, c, start, slotEnd).allowed());
            if (!playable) {
                skippedTitles.add(a.title());
                if (++skippedInRow >= candidates.size()) { notices.add("Stopped early: no remaining video is licensed for this channel at " + fmt(t, ch.timezone()) + "."); break; }
                continue;
            }
            skippedInRow = 0;
            long pos = 0;
            List<Long> bounds = new ArrayList<>(cues);
            bounds.add(a.durationMs());
            for (long cut : bounds) {
                items.add(new Item("PROGRAM", a.id(), pos, t, t.plusMillis(cut - pos), null));
                t = t.plusMillis(cut - pos);
                pos = cut;
                if (cut < a.durationMs() && adsOk && fits(adPerHour, t, ch)) {
                    items.add(new Item("AD_BREAK", a.id(), 0, t, t.plusSeconds(ch.breakSeconds()), provider ? "PROVIDER" : "DISTRIBUTOR"));
                    adPerHour.merge(hourOf(t), ch.breakSeconds() * 1000L, Long::sum);
                    t = t.plusSeconds(ch.breakSeconds());
                    provider = !provider;
                }
            }
            if (fits(adPerHour, t, ch) && t.isBefore(until)) {
                items.add(new Item("AD_BREAK", null, 0, t, t.plusSeconds(ch.breakSeconds()), provider ? "PROVIDER" : "DISTRIBUTOR"));
                adPerHour.merge(hourOf(t), ch.breakSeconds() * 1000L, Long::sum);
                t = t.plusSeconds(ch.breakSeconds());
                provider = !provider;
            }
        }
        if (!skippedTitles.isEmpty())
            notices.add("Skipped because their licence doesn't cover this channel's countries or times: " + String.join(", ", skippedTitles) + ".");
        notices.add("Added " + items.stream().filter(x -> x.kind().equals("PROGRAM")).map(Item::assetId).count() + " programme parts and "
                + items.stream().filter(x -> x.kind().equals("AD_BREAK")).count() + " ad breaks, up to " + fmt(t, ch.timezone()) + ".");
        return new Plan(items, notices, t);
    }

    static boolean fits(Map<Long, Long> adPerHour, Instant t, Channel ch) {
        return adPerHour.getOrDefault(hourOf(t), 0L) + ch.breakSeconds() * 1000L <= ch.adSecondsPerHour() * 1000L;
    }
}
