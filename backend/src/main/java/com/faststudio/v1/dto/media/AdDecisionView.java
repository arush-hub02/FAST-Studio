package com.faststudio.v1.dto.media;

import com.faststudio.v1.services.ads.AdDecisionServiceV1;


import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Tracking URLs stay in server-side decisions for reporting; they never belong in a player/browser response. */
public record AdDecisionView(UUID breakId, long breakMs, List<Slot> slots, long filledMs,
                             List<AdDecisionServiceV1.Excluded> excluded, String summary) {
    /** delivery R2: mediaKey is our copy; DIRECT: hlsUrl is the ad server's own HLS (mediaKey null). */
    public record Slot(UUID campaignId, String campaign, String advertiser, String mediaKey,
                       long durationMs, BigDecimal cpm, String source, List<Integer> levels, String delivery, String hlsUrl) {}
    public static AdDecisionView from(AdDecisionServiceV1.Decision decision) {
        if (decision == null) return null;
        return new AdDecisionView(decision.breakId(), decision.breakMs(), decision.slots().stream()
                .map(s -> new Slot(s.campaignId(), s.campaign(), s.advertiser(), s.mediaKey(),
                        s.durationMs(), s.cpm(), s.source(), s.levels(), s.delivery(), s.hlsUrl())).toList(),
                decision.filledMs(), decision.excluded(), decision.summary());
    }
}
