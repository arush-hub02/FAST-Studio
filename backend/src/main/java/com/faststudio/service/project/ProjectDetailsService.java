package com.faststudio.service.project;

import com.faststudio.catalog.MediaSettings;
import com.faststudio.core.Feature;
import com.faststudio.core.Tenant;
import com.faststudio.core.TenantScope;
import com.faststudio.dto.project.ProjectDetails;
import com.faststudio.edge.EdgeClient;
import com.faststudio.repository.project.ProjectDetailsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectDetailsService {
    private final ProjectDetailsRepository repository;
    private final MediaSettings settings;
    private final EdgeClient edge;

    public ProjectDetailsService(ProjectDetailsRepository repository, MediaSettings settings, EdgeClient edge) {
        this.repository = repository; this.settings = settings; this.edge = edge;
    }

    @Transactional(readOnly = true)
    public ProjectDetails tenant(Tenant tenant) {
        tenant.require(Tenant.Perm.VIEW);
        return details(tenant.orgId(), tenant.role().name(),
                tenant.role().perms.stream().map(Enum::name).sorted().toList(), false);
    }

    /** Called only behind the existing platform-admin interceptor; does not grant tenant membership. */
    @Transactional(readOnly = true)
    public ProjectDetails platform(UUID org) {
        return TenantScope.as(org, () -> details(org, "PLATFORM_ADMIN", List.of("PROJECT_VIEW", "CONNECTORS_MANAGE"), true));
    }

    private ProjectDetails details(UUID org, String role, List<String> permissions, boolean platform) {
        var row = repository.project(org);
        var features = Feature.parse(row.features());
        var media = settings.view(org);
        List<ProjectDetails.Library> libraries = List.of();
        if (platform || features.contains(Feature.LIBRARY_SYNC.name())) {
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
        return new ProjectDetails(1, new ProjectDetails.Project(row.id(), row.slug(), row.name(), row.kind(), row.status()),
                new ProjectDetails.Access(role, permissions, features),
                new ProjectDetails.Media(media.types().stream()
                        .map(t -> new ProjectDetails.MediaType(t.key(), t.label(), t.kind())).toList(),
                        media.enabledTypes(), media.defaultType(), media.premiereTime(), media.weekdays(),
                        media.personalizedAds(), media.viewerKeySet(), media.revision()), connectors, repository.summary(org));
    }
}
