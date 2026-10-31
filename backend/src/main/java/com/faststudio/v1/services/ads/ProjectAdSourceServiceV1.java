package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The ad demand waterfall as a project sees it. Connectors are managed by the platform administrator
 * (ConnectorAdminServiceV1). A project sees its waterfall only with AD_WATERFALL_VIEW or AD_OWN_TAGS, and adds its own
 * VAST tags only with AD_OWN_TAGS. Platform-managed tag URLs are never shown to the project.
 */
@Service
public class ProjectAdSourceServiceV1 {
    private final AdSourceAdminService adSources;
    private final TenantConfigurationServiceV1 configuration;
    private final AssetRepository projects;

    public ProjectAdSourceServiceV1(AdSourceAdminService adSources, TenantConfigurationServiceV1 configuration, AssetRepository projects) {
        this.adSources = adSources;
        this.configuration = configuration;
        this.projects = projects;
    }

    /** At least one of the features must be switched on (and included in the tenant's plan). */
    private void requireFeature(Tenant t, Feature... any) {
        List<String> on = configuration.effectiveFeatures(t.orgId(), projects.projectFeatures(t.orgId()));
        if (Arrays.stream(any).noneMatch(f -> on.contains(f.name())))
            throw new ApiException(HttpStatus.FORBIDDEN, "FEATURE_OFF", "This isn't switched on for your company. Ask the platform administrator.");
    }

    public List<IAdSource.Config> list(Tenant t) {
        requireFeature(t, Feature.AD_WATERFALL_VIEW, Feature.AD_OWN_TAGS);
        return adSources.list(t.orgId(), false);
    }

    public UUID add(Tenant t, AdSourceAdminService.AdSourceRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        requireFeature(t, Feature.AD_OWN_TAGS);
        return adSources.add(t.orgId(), t.userId(), r, "TENANT");
    }

    public void update(Tenant t, UUID id, AdSourceAdminService.AdSourceRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        requireFeature(t, Feature.AD_OWN_TAGS);
        adSources.update(t.orgId(), t.userId(), id, r, "TENANT");
    }

    public void delete(Tenant t, UUID id) {
        t.require(Tenant.Perm.ADS_EDIT);
        requireFeature(t, Feature.AD_OWN_TAGS);
        adSources.delete(t.orgId(), t.userId(), id, "TENANT");
    }

    public AdSourceAdminService.TagTest test(Tenant t, AdSourceAdminService.TagTestRequest r) {
        t.require(Tenant.Perm.ADS_EDIT);
        requireFeature(t, Feature.AD_OWN_TAGS);
        return adSources.test(t.orgId(), r);
    }
}
