package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Data.Entry;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.ads.AdsDto.*;
import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.repositories.ads.CampaignRepository;
import com.faststudio.v1.services.media.MediaSigner;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Direct-sold campaigns of a project and the "what would play in the next break?" simulation. */
@Service
public class CampaignServiceV1 {
    private final Data data;
    private final CampaignRepository repository;
    private final AdDecisionServiceV1 decisions;
    private final MediaSigner signer;
    private final Audit audit;

    public CampaignServiceV1(Data data, CampaignRepository repository, AdDecisionServiceV1 decisions, MediaSigner signer, Audit audit) {
        this.data = data;
        this.repository = repository;
        this.decisions = decisions;
        this.signer = signer;
        this.audit = audit;
    }

    public List<Campaign> list(Tenant t) {
        return repository.campaigns(t.orgId()).stream()
                .map(c -> c.mediaKey() == null ? c : c.withThumb(signer.url(c.mediaKey(), "thumb.jpg"))).toList();
    }

    public Map<String, UUID> create(Tenant t, CampaignRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        validate(t, r);
        UUID id = repository.insert(t.orgId(), fields(r, ViewerContextService.audiences(r.audiences()), r.frequencyCap() == null ? 0 : r.frequencyCap()));
        audit.log(t, "campaign.create", "campaign", id, r.name());
        return Map.of("id", id);
    }

    public Map<String, UUID> update(Tenant t, UUID id, CampaignRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        validate(t, r);
        String audiences = r.audiences() == null ? null : ViewerContextService.audiences(r.audiences());
        if (!repository.update(t.orgId(), id, fields(r, audiences, r.frequencyCap()))) throw ApiException.notFound("Campaign");
        audit.log(t, "campaign.update", "campaign", id, r.name());
        return Map.of("id", id);
    }

    private static CampaignRepository.Fields fields(CampaignRequest r, String audiences, Integer frequencyCap) {
        return new CampaignRepository.Fields(r.name().trim(), r.advertiser().trim(), r.creativeAssetId(), r.startsAt(), r.endsAt(),
                territories(r.territories()), r.priority(), r.cpm(), audiences, frequencyCap);
    }

    /** The creative must be an Ad video of this project, and the campaign must end after it starts. */
    private void validate(Tenant t, CampaignRequest r) {
        var creative = data.asset(t.orgId(), r.creativeAssetId());
        if (!creative.kind().equals("AD")) throw ApiException.bad("The creative must be a video of type 'Ad'.");
        if (!r.endsAt().isAfter(r.startsAt())) throw ApiException.bad("The campaign must end after it starts.");
    }

    /** Empty means worldwide ("WW"). */
    private static String territories(List<String> t) {
        String csv = Times.csv(t);
        return csv.isEmpty() ? "WW" : csv;
    }

    public Map<String, String> setStatus(Tenant t, UUID id, StatusRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        if (!repository.setStatus(t.orgId(), id, r.status())) throw ApiException.notFound("Campaign");
        audit.log(t, "campaign." + r.status().toLowerCase(), "campaign", id, "");
        return Map.of("status", r.status());
    }

    /** Runs the ad decision for the channel's next published break, with a reason for every campaign left out. */
    public Simulation simulate(Tenant t, SimulateRequest r) {
        var ch = data.channel(t.orgId(), r.channelId());
        if (ch.publishedVersionId() == null) throw ApiException.conflict("NO_SCHEDULE", "Publish a schedule with ad breaks first.");
        Instant now = Instant.now();
        Entry b = data.entries(t.orgId(), ch.publishedVersionId(), now, now.plusSeconds(48 * 3600)).stream()
                .filter(e -> e.isBreak() && e.end().isAfter(now)).findFirst()
                .orElseThrow(() -> ApiException.conflict("NO_BREAKS", "No upcoming ad breaks in the published schedule."));
        String programme = b.assetId() == null ? "Between programmes" : "Inside \"" + data.asset(t.orgId(), b.assetId()).title() + "\"";
        var d = decisions.decide(t.orgId(), null, b.id(), b.start(), b.durationMs(), b.assetId(), r.country().toUpperCase());
        return new Simulation(b.start(), b.durationMs() / 1000, b.ownership(), programme, AdDecisionView.from(d), true);
    }
}
