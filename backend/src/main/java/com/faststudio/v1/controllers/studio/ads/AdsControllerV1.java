package com.faststudio.v1.controllers.studio.ads;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.ads.AdsDto.*;
import com.faststudio.v1.services.ads.AdSourceAdminService;
import com.faststudio.v1.services.ads.CampaignServiceV1;
import com.faststudio.v1.services.ads.ProjectAdSourceServiceV1;
import com.faststudio.v1.services.ads.sources.IAdSource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ads screen of a project: its ad demand waterfall, direct-sold campaigns and decision simulation. */
@RestController
@RequestMapping("/api/orgs/{org}")
public class AdsControllerV1 {
    private final ProjectAdSourceServiceV1 adSources;
    private final CampaignServiceV1 campaigns;

    public AdsControllerV1(ProjectAdSourceServiceV1 adSources, CampaignServiceV1 campaigns) {
        this.adSources = adSources;
        this.campaigns = campaigns;
    }

    // ------------------------------------------------------------------ ad sources (feature-gated)

    @GetMapping("/ad-sources")
    List<IAdSource.Config> sources(Tenant t) { return adSources.list(t); }

    @PostMapping("/ad-sources")
    Map<String, UUID> addSource(Tenant t, @Valid @RequestBody AdSourceAdminService.AdSourceRequest r) { return Map.of("id", adSources.add(t, r)); }

    @PutMapping("/ad-sources/{id}")
    Map<String, UUID> updateSource(Tenant t, @PathVariable UUID id, @Valid @RequestBody AdSourceAdminService.AdSourceRequest r) {
        adSources.update(t, id, r);
        return Map.of("id", id);
    }

    @DeleteMapping("/ad-sources/{id}")
    Map<String, UUID> deleteSource(Tenant t, @PathVariable UUID id) {
        adSources.delete(t, id);
        return Map.of("id", id);
    }

    @PostMapping("/ad-sources/test")
    AdSourceAdminService.TagTest test(Tenant t, @Valid @RequestBody AdSourceAdminService.TagTestRequest r) { return adSources.test(t, r); }

    // ------------------------------------------------------------------ campaigns

    @GetMapping("/campaigns")
    List<Campaign> list(Tenant t) { return campaigns.list(t); }

    @PostMapping("/campaigns")
    Map<String, UUID> create(Tenant t, @Valid @RequestBody CampaignRequest r) { return campaigns.create(t, r); }

    @PutMapping("/campaigns/{id}")
    Map<String, UUID> update(Tenant t, @PathVariable UUID id, @Valid @RequestBody CampaignRequest r) { return campaigns.update(t, id, r); }

    @PostMapping("/campaigns/{id}/status")
    Map<String, String> status(Tenant t, @PathVariable UUID id, @Valid @RequestBody StatusRequest r) { return campaigns.setStatus(t, id, r); }

    @PostMapping("/ads/simulate")
    Simulation simulate(Tenant t, @Valid @RequestBody SimulateRequest r) { return campaigns.simulate(t, r); }
}
