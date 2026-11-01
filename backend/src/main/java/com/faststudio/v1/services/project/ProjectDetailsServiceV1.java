package com.faststudio.v1.services.project;

import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.project.ProjectDetails;
import com.faststudio.v1.repositories.project.ProjectDetailsRepository;
import com.faststudio.v1.repositories.tenancy.TenantConfigurationRepository;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;
import com.faststudio.v1.services.tenancy.CapabilityServiceV1;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.cloudflare.EdgeClient;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Builds the "project bootstrap" the browser loads after sign-in or project switch: who am I here, which media
 * types/settings apply, which connectors are usable, and the resolved tenant/project configuration.
 * Everything returned is an allowlisted projection; credentials never leave the server (docs/PROJECT_BOOTSTRAP_SECURITY.md).
 */
@Service
public class ProjectDetailsServiceV1 {
    private final ProjectDetailsRepository repository;
    private final MediaSettingsServiceV1 settings;
    private final EdgeClient edge;
    private final TenantConfigurationServiceV1 configuration;
    private final TenantConfigurationRepository tenancy;
    private final CapabilityServiceV1 capabilities;

    public ProjectDetailsServiceV1(ProjectDetailsRepository repository, MediaSettingsServiceV1 settings, EdgeClient edge,
                                     TenantConfigurationServiceV1 configuration, TenantConfigurationRepository tenancy, CapabilityServiceV1 capabilities) {
        this.capabilities = capabilities;
        this.repository = repository;
        this.settings = settings;
        this.edge = edge;
        this.configuration = configuration;
        this.tenancy = tenancy;
    }

    /** Bootstrap for a member of the project; permissions include any custom-role reductions. */
    @Transactional(readOnly = true)
    public ProjectDetails tenant(Tenant tenant) {
        tenant.require(Tenant.Perm.VIEW);
        return details(tenant.orgId(), tenant.role().name(),
                capabilities.permissions(tenant), false);
    }

    /** Called only behind the platform-admin interceptor; does not grant tenant membership. */
    @Transactional(readOnly = true)
    public ProjectDetails platform(UUID org) {
        return TenantScope.as(org, () -> details(org, "PLATFORM_ADMIN", List.of("PROJECT_VIEW", "CONNECTORS_MANAGE"), true));
    }

    private ProjectDetails details(UUID org, String role, List<String> permissions, boolean platform) {
        var row = repository.project(org);
        // Features are what the project switched on, limited to what the tenant's plan includes.
        var features = configuration.effectiveFeatures(org, row.features());
        var media = settings.view(org);

        // Media library is shown only when the plan feature is on AND the Mux connector is enabled for this project.
        List<ProjectDetails.Library> libraries = List.of();
        if ((platform || features.contains(Feature.LIBRARY_SYNC.name())) && configuration.driverEnabled(org, "MUX")) {
            var library = repository.library(org);
            boolean canSync = library.credentialsConfigured() && (platform || permissions.contains("ASSETS_EDIT"));
            libraries = List.of(new ProjectDetails.Library("MUX",
                    library.credentialsConfigured() ? "CONFIGURED" : "NOT_CONFIGURED", library.environment(),
                    library.credentialsConfigured(), library.webhookConfigured(),
                    canSync ? List.of("CATALOG_SYNC", "DIRECT_UPLOAD") : List.of()));
        }
        boolean canViewAds = platform || features.contains(Feature.AD_WATERFALL_VIEW.name())
                || features.contains(Feature.AD_OWN_TAGS.name());
        var connectors = new ProjectDetails.Connectors(libraries,
                canViewAds ? repository.advertising(org) : List.of(),
                new ProjectDetails.Delivery("R2", "CLOUDFLARE", edge.configured()));

        // schemaVersion 2 adds the resolved tenant/project configuration.
        return new ProjectDetails(2,
                new ProjectDetails.Project(row.id(), row.slug(), row.name(), row.kind(), row.status()),
                new ProjectDetails.Access(role, permissions, features),
                new ProjectDetails.Media(media.types().stream()
                        .map(t -> new ProjectDetails.MediaType(t.key(), t.label(), t.kind())).toList(),
                        media.enabledTypes(), media.defaultType(), media.premiereTime(), media.weekdays(),
                        media.personalizedAds(), media.viewerKeySet(), media.revision()),
                connectors, repository.summary(org),
                configuration.settings(tenancy.tenant(org), org));
    }
}
