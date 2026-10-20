package com.faststudio.ads;

import com.faststudio.core.Data;
import com.faststudio.core.Times;
import com.faststudio.rights.Rights;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local stand-in for an ad decision server (ADS). Picks eligible campaigns for one break and explains every exclusion.
 * In production this is a VAST request to a real ad server; the stitcher logic around it stays the same.
 */
@Service
public class AdDecisionService {
    private final Data data;
    // ponytail: decisions cached in memory per (session, break) so playlists stay stable; persist when running >1 node.
    private final Map<String, Decision> cache = new ConcurrentHashMap<>();

    public AdDecisionService(Data data) {
        this.data = data;
    }

    public record Slot(UUID campaignId, String campaign, String advertiser, String mediaKey, long durationMs, BigDecimal cpm) {
        public int segments() { return (int) (durationMs / Times.SEG_MS); }
    }

    public record Excluded(String campaign, String reason) {}

    public record Decision(UUID breakId, long breakMs, List<Slot> slots, long filledMs, List<Excluded> excluded, String summary) {}

    record Candidate(UUID id, String name, String advertiser, String status, OffsetDateTime startsAt, OffsetDateTime endsAt,
                     String territories, int priority, BigDecimal cpm, String creativeStatus, String mediaKey, long durationMs) {}

    public Decision cached(UUID sessionId, UUID breakId) {
        return cache.get(sessionId + ":" + breakId);
    }

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country) {
        if (sessionId == null) return compute(org, null, breakId, breakStart, breakMs, programmeAssetId, country);
        if (cache.size() > 50_000) cache.clear();
        return cache.computeIfAbsent(sessionId + ":" + breakId, k -> compute(org, sessionId, breakId, breakStart, breakMs, programmeAssetId, country));
    }

    Decision compute(UUID org, UUID sessionId, UUID breakId, Instant at, long breakMs, UUID programmeAssetId, String country) {
        List<Excluded> excluded = new ArrayList<>();
        if (programmeAssetId != null) {
            Rights.Policy p = data.policy(org, programmeAssetId);
            if (p == null || !p.adsAllowed())
                return new Decision(breakId, breakMs, List.of(), 0, List.of(), "No ads: the programme's licence doesn't allow advertising.");
        }
        List<Candidate> all = data.db.sql("""
                select c.id, c.name, c.advertiser, c.status, c.starts_at, c.ends_at, c.territories, c.priority, c.cpm,
                       a.status as creative_status, a.media_key, a.duration_ms
                from campaign c join asset a on a.org_id = c.org_id and a.id = c.creative_asset_id
                where c.org_id = :o""").param("o", org).query(Candidate.class).list();
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : all) {
            List<String> terr = Times.codes(c.territories());
            if (!c.status().equals("ACTIVE")) excluded.add(new Excluded(c.name(), "Campaign is " + c.status().toLowerCase()));
            else if (at.isBefore(c.startsAt().toInstant()) || !at.isBefore(c.endsAt().toInstant())) excluded.add(new Excluded(c.name(), "Outside its flight dates"));
            else if (!terr.contains("WW") && !terr.contains(country)) excluded.add(new Excluded(c.name(), "Not targeting " + country + " (targets " + String.join(", ", terr) + ")"));
            else if (!"READY".equals(c.creativeStatus())) excluded.add(new Excluded(c.name(), "Creative video isn't processed yet, so it can't be stitched"));
            else eligible.add(c);
        }
        // highest priority first; equal priorities rotate per viewer and break so delivery is spread out
        Random rnd = new Random(Objects.hash(sessionId, breakId));
        Collections.shuffle(eligible, rnd);
        eligible.sort(Comparator.comparingInt(Candidate::priority).reversed());
        List<Slot> slots = new ArrayList<>();
        Set<String> advertisers = new HashSet<>();
        long left = breakMs;
        for (Candidate c : eligible) {
            long d = c.durationMs();
            if (advertisers.contains(c.advertiser())) excluded.add(new Excluded(c.name(), "Same advertiser already in this break (competitive separation)"));
            else if (d > left) excluded.add(new Excluded(c.name(), d / 1000 + " s ad doesn't fit the " + left / 1000 + " s left"));
            else {
                slots.add(new Slot(c.id(), c.name(), c.advertiser(), c.mediaKey(), d, c.cpm()));
                advertisers.add(c.advertiser());
                left -= d;
            }
        }
        long filled = breakMs - left;
        String summary = slots.isEmpty() ? "Unfilled: no eligible ads. The standby slate plays for the whole break."
                : "Filled " + filled / 1000 + " of " + breakMs / 1000 + " s with " + slots.size() + " ad(s)" + (left > 0 ? "; slate covers the last " + left / 1000 + " s." : ".");
        return new Decision(breakId, breakMs, slots, filled, excluded, summary);
    }
}
