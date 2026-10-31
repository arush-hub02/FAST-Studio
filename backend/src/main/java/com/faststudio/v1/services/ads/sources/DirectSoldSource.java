package com.faststudio.v1.services.ads.sources;

import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.dto.ads.AdsDto.Candidate;
import com.faststudio.v1.repositories.ads.CampaignRepository;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** Ad connector for our own direct-sold campaigns: picks eligible campaigns and explains every exclusion. */
@Component
public class DirectSoldSource implements IAdSource {
    private final CampaignRepository campaigns;

    public DirectSoldSource(CampaignRepository campaigns) {
        this.campaigns = campaigns;
    }

    @Override
    public String kind() { return "DIRECT"; }

    @Override
    public Fill fill(Config source, Request req) {
        List<AdDecisionServiceV1.Excluded> excluded = new ArrayList<>();
        List<Candidate> all = campaigns.candidates(req.org());
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : all) {
            List<String> terr = Times.codes(c.territories());
            if (!c.status().equals("ACTIVE")) excluded.add(new AdDecisionServiceV1.Excluded(c.name(), "Campaign is " + c.status().toLowerCase()));
            else if (req.at().isBefore(c.startsAt().toInstant()) || !req.at().isBefore(c.endsAt().toInstant())) excluded.add(new AdDecisionServiceV1.Excluded(c.name(), "Outside its flight dates"));
            else if (!terr.contains("WW") && !terr.contains(req.country())) excluded.add(new AdDecisionServiceV1.Excluded(c.name(), "Not targeting " + req.country() + " (targets " + String.join(", ", terr) + ")"));
            else if (!"READY".equals(c.creativeStatus())) excluded.add(new AdDecisionServiceV1.Excluded(c.name(), "Creative video isn't processed yet, so it can't be stitched"));
            else if (!Times.codes(c.audiences()).isEmpty() && Collections.disjoint(Times.codes(c.audiences()),req.viewer().audiences()))
                excluded.add(new AdDecisionServiceV1.Excluded(c.name(), "Viewer is not in this campaign's permitted audience"));
            else if (c.frequencyCap()>0 && reserved(req,c.id())>=c.frequencyCap())
                excluded.add(new AdDecisionServiceV1.Excluded(c.name(), "Viewer frequency cap reached for the last hour"));
            else eligible.add(c);
        }
        // highest priority first; equal priorities rotate per viewer and break so delivery is spread out
        Random rnd = new Random(Objects.hash(req.sessionId(), req.breakId()));
        Collections.shuffle(eligible, rnd);
        eligible.sort(Comparator.comparingInt(Candidate::priority).reversed());
        List<AdDecisionServiceV1.Slot> slots = new ArrayList<>();
        long left = req.remainingMs();
        for (Candidate c : eligible) {
            long d = c.durationMs();
            if (req.advertisers().contains(c.advertiser())) excluded.add(new AdDecisionServiceV1.Excluded(c.name(), "Same advertiser already in this break (competitive separation)"));
            else if (d > left) excluded.add(new AdDecisionServiceV1.Excluded(c.name(), d / 1000 + " s ad doesn't fit the " + left / 1000 + " s left"));
            else {
                slots.add(new AdDecisionServiceV1.Slot(c.id(), c.name(), c.advertiser(), c.mediaKey(), d, c.cpm(), source.name(), Map.of()));
                req.advertisers().add(c.advertiser());
                left -= d;
            }
        }
        return new Fill(slots, excluded);
    }

    private long reserved(Request req, UUID campaign) {
        if(req.sessionId()==null) return 0;
        return campaigns.servedLastHour(req.org(), req.viewer().key(), req.sessionId(), campaign);
    }
}
