package com.faststudio.ads;

import com.faststudio.core.Data;
import com.faststudio.core.Times;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** Ad connector for our own direct-sold campaigns: picks eligible campaigns and explains every exclusion. */
@Component
public class DirectSoldSource implements AdSource {
    private final Data data;

    public DirectSoldSource(Data data) {
        this.data = data;
    }

    @Override
    public String kind() { return "DIRECT"; }

    record Candidate(UUID id, String name, String advertiser, String status, OffsetDateTime startsAt, OffsetDateTime endsAt,
                     String territories, int priority, BigDecimal cpm, String creativeStatus, String mediaKey, long durationMs,
                     String audiences, int frequencyCap) {}

    @Override
    public Fill fill(Config source, Request req) {
        List<AdDecisionService.Excluded> excluded = new ArrayList<>();
        List<Candidate> all = data.db.sql("""
                select c.id, c.name, c.advertiser, c.status, c.starts_at, c.ends_at, c.territories, c.priority, c.cpm,
                       a.status as creative_status, a.media_key, a.duration_ms,c.audiences,c.frequency_cap
                from campaign c join asset a on a.org_id = c.org_id and a.id = c.creative_asset_id
                where c.org_id = :o""").param("o", req.org()).query(Candidate.class).list();
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : all) {
            List<String> terr = Times.codes(c.territories());
            if (!c.status().equals("ACTIVE")) excluded.add(new AdDecisionService.Excluded(c.name(), "Campaign is " + c.status().toLowerCase()));
            else if (req.at().isBefore(c.startsAt().toInstant()) || !req.at().isBefore(c.endsAt().toInstant())) excluded.add(new AdDecisionService.Excluded(c.name(), "Outside its flight dates"));
            else if (!terr.contains("WW") && !terr.contains(req.country())) excluded.add(new AdDecisionService.Excluded(c.name(), "Not targeting " + req.country() + " (targets " + String.join(", ", terr) + ")"));
            else if (!"READY".equals(c.creativeStatus())) excluded.add(new AdDecisionService.Excluded(c.name(), "Creative video isn't processed yet, so it can't be stitched"));
            else if (!Times.codes(c.audiences()).isEmpty() && Collections.disjoint(Times.codes(c.audiences()),req.viewer().audiences()))
                excluded.add(new AdDecisionService.Excluded(c.name(), "Viewer is not in this campaign's permitted audience"));
            else if (c.frequencyCap()>0 && reserved(req,c.id())>=c.frequencyCap())
                excluded.add(new AdDecisionService.Excluded(c.name(), "Viewer frequency cap reached for the last hour"));
            else eligible.add(c);
        }
        // highest priority first; equal priorities rotate per viewer and break so delivery is spread out
        Random rnd = new Random(Objects.hash(req.sessionId(), req.breakId()));
        Collections.shuffle(eligible, rnd);
        eligible.sort(Comparator.comparingInt(Candidate::priority).reversed());
        List<AdDecisionService.Slot> slots = new ArrayList<>();
        long left = req.remainingMs();
        for (Candidate c : eligible) {
            long d = c.durationMs();
            if (req.advertisers().contains(c.advertiser())) excluded.add(new AdDecisionService.Excluded(c.name(), "Same advertiser already in this break (competitive separation)"));
            else if (d > left) excluded.add(new AdDecisionService.Excluded(c.name(), d / 1000 + " s ad doesn't fit the " + left / 1000 + " s left"));
            else {
                slots.add(new AdDecisionService.Slot(c.id(), c.name(), c.advertiser(), c.mediaKey(), d, c.cpm(), source.name(), Map.of()));
                req.advertisers().add(c.advertiser());
                left -= d;
            }
        }
        return new Fill(slots, excluded);
    }

    private long reserved(Request req, UUID campaign) {
        if(req.sessionId()==null) return 0;
        return data.db.sql("""
            select count(*) from ad_decision d join playback_session s on s.org_id=d.org_id and s.id=d.session_id
            where d.org_id=:o and d.created_at>now()-interval '1 hour'
              and ((cast(:key as text) is not null and s.viewer_key=:key) or (cast(:key as text) is null and s.id=:sid))
              and exists (select 1 from jsonb_array_elements(d.decision::jsonb->'slots') x where x->>'campaignId'=:camp)""")
                .param("o",req.org()).param("key",req.viewer().key()).param("sid",req.sessionId()).param("camp",campaign.toString()).query(Long.class).single();
    }
}
