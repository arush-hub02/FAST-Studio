package com.faststudio.ads;

import com.faststudio.core.*;
import com.faststudio.core.Data.Entry;
import com.faststudio.media.MediaSigner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/orgs/{org}")
public class AdsController {
    private final Data data;
    private final Audit audit;
    private final AdDecisionService decisions;
    private final MediaSigner signer;

    public AdsController(Data data, Audit audit, AdDecisionService decisions, MediaSigner signer) {
        this.data = data;
        this.audit = audit;
        this.decisions = decisions;
        this.signer = signer;
    }

    public record Campaign(UUID id, String name, String advertiser, String status, OffsetDateTime startsAt, OffsetDateTime endsAt,
                           String territories, int priority, BigDecimal cpm, UUID creativeAssetId, String creativeTitle,
                           String creativeStatus, long creativeDurationMs, String creativeColor, String mediaKey,
                           long impressions, long completes, String thumbUrl) {}

    @GetMapping("/campaigns")
    List<Campaign> list(Tenant t) {
        // a completion only counts when its start was also recorded (client beacons can miss the start on a mid-ad join)
        return data.db.sql("""
                select c.id, c.name, c.advertiser, c.status, c.starts_at, c.ends_at, c.territories, c.priority, c.cpm, c.creative_asset_id,
                       a.title as creative_title, a.status as creative_status, a.duration_ms as creative_duration_ms, a.color as creative_color, a.media_key,
                       (select count(*) from ad_event e where e.org_id = c.org_id and e.campaign_id = c.id and e.event = 'IMPRESSION') as impressions,
                       (select count(*) from ad_event e where e.org_id = c.org_id and e.campaign_id = c.id and e.event = 'COMPLETE'
                          and exists (select 1 from ad_event i where i.session_id = e.session_id and i.break_id = e.break_id
                                      and i.campaign_id = e.campaign_id and i.event = 'IMPRESSION')) as completes,
                       null as thumb_url
                from campaign c join asset a on a.org_id = c.org_id and a.id = c.creative_asset_id
                where c.org_id = :o order by c.status, c.priority desc, c.name""").param("o", t.orgId()).query(Campaign.class).list()
                .stream().map(c -> c.mediaKey() == null ? c : new Campaign(c.id(), c.name(), c.advertiser(), c.status(), c.startsAt(), c.endsAt(),
                        c.territories(), c.priority(), c.cpm(), c.creativeAssetId(), c.creativeTitle(), c.creativeStatus(), c.creativeDurationMs(),
                        c.creativeColor(), c.mediaKey(), c.impressions(), c.completes(), signer.url(c.mediaKey(), "thumb.jpg"))).toList();
    }

    public record CampaignRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 80) String advertiser, @NotNull UUID creativeAssetId,
                                  @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt, List<String> territories,
                                  @Min(1) @Max(10) int priority, @NotNull @DecimalMin("0") @DecimalMax("500") BigDecimal cpm) {}

    @PostMapping("/campaigns")
    Map<String, UUID> create(Tenant t, @Valid @RequestBody CampaignRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        validate(t, r);
        UUID id = data.db.sql("""
                insert into campaign(org_id, name, advertiser, creative_asset_id, starts_at, ends_at, territories, priority, cpm)
                values (:o,:n,:a,:cr,:s,:e,:terr,:p,:cpm) returning id""")
                .param("o", t.orgId()).param("n", r.name().trim()).param("a", r.advertiser().trim()).param("cr", r.creativeAssetId())
                .param("s", r.startsAt()).param("e", r.endsAt()).param("terr", terr(r.territories())).param("p", r.priority()).param("cpm", r.cpm())
                .query(UUID.class).single();
        audit.log(t, "campaign.create", "campaign", id, r.name());
        return Map.of("id", id);
    }

    @PutMapping("/campaigns/{id}")
    Map<String, UUID> update(Tenant t, @PathVariable UUID id, @Valid @RequestBody CampaignRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        validate(t, r);
        int n = data.db.sql("""
                update campaign set name=:n, advertiser=:a, creative_asset_id=:cr, starts_at=:s, ends_at=:e, territories=:terr, priority=:p, cpm=:cpm
                where org_id=:o and id=:id""")
                .param("n", r.name().trim()).param("a", r.advertiser().trim()).param("cr", r.creativeAssetId()).param("s", r.startsAt())
                .param("e", r.endsAt()).param("terr", terr(r.territories())).param("p", r.priority()).param("cpm", r.cpm())
                .param("o", t.orgId()).param("id", id).update();
        if (n == 0) throw ApiException.notFound("Campaign");
        audit.log(t, "campaign.update", "campaign", id, r.name());
        return Map.of("id", id);
    }

    private void validate(Tenant t, CampaignRequest r) {
        var creative = data.asset(t.orgId(), r.creativeAssetId());
        if (!creative.kind().equals("AD")) throw ApiException.bad("The creative must be a video of type 'Ad'.");
        if (!r.endsAt().isAfter(r.startsAt())) throw ApiException.bad("The campaign must end after it starts.");
    }

    static String terr(List<String> t) {
        String csv = Times.csv(t);
        return csv.isEmpty() ? "WW" : csv;
    }

    public record StatusRequest(@Pattern(regexp = "ACTIVE|PAUSED|ENDED") String status) {}

    @PostMapping("/campaigns/{id}/status")
    Map<String, String> status(Tenant t, @PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        int n = data.db.sql("update campaign set status=:s where org_id=:o and id=:id").param("s", r.status()).param("o", t.orgId()).param("id", id).update();
        if (n == 0) throw ApiException.notFound("Campaign");
        audit.log(t, "campaign." + r.status().toLowerCase(), "campaign", id, "");
        return Map.of("status", r.status());
    }

    public record SimulateRequest(@NotNull UUID channelId, @NotBlank String country) {}

    public record Simulation(Instant breakStart, long breakSeconds, String ownership, String programme, AdDecisionService.Decision decision, boolean simulated) {}

    /** Runs the ad decision for the channel's next published break, with a reason for every campaign left out. */
    @PostMapping("/ads/simulate")
    Simulation simulate(Tenant t, @Valid @RequestBody SimulateRequest r) {
        var ch = data.channel(t.orgId(), r.channelId());
        if (ch.publishedVersionId() == null) throw ApiException.conflict("NO_SCHEDULE", "Publish a schedule with ad breaks first.");
        Instant now = Instant.now();
        Entry b = data.entries(t.orgId(), ch.publishedVersionId(), now, now.plusSeconds(48 * 3600)).stream()
                .filter(e -> e.isBreak() && e.end().isAfter(now)).findFirst()
                .orElseThrow(() -> ApiException.conflict("NO_BREAKS", "No upcoming ad breaks in the published schedule."));
        String programme = b.assetId() == null ? "Between programmes" : "Inside \"" + data.asset(t.orgId(), b.assetId()).title() + "\"";
        var d = decisions.decide(t.orgId(), null, b.id(), b.start(), b.durationMs(), b.assetId(), r.country().toUpperCase());
        return new Simulation(b.start(), b.durationMs() / 1000, b.ownership(), programme, d, true);
    }
}
