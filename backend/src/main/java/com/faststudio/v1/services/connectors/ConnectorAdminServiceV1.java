package com.faststudio.v1.services.connectors;

import com.faststudio.v1.repositories.tenancy.CapabilityRepository;
import com.faststudio.v1.dto.tenancy.CapabilityDto.Capability;
import java.util.stream.Stream;
import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.connectors.ConnectorsDto.*;
import com.faststudio.v1.repositories.connectors.ConnectorRepository;
import com.faststudio.v1.services.ads.AdSourceAdminService;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.cloudflare.EdgeClient;
import com.faststudio.v1.util.cloudflare.EdgePublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Platform administrator's Connectors page for one customer project: its ad demand waterfall (direct-sold plus external
 * ad servers), media storage status, and the feature switches that decide what the project itself sees.
 * Projects never configure connectors: credentials and tag URLs stay with the platform.
 * Every call checks platform-admin rights, then works as that project under row-level security.
 */
@Service
public class ConnectorAdminServiceV1 {
    private final ConnectorRepository repository;
    private final AdSourceAdminService adSources;
    private final TenantConfigurationServiceV1 configuration;
    private final EdgeClient edge;
    private final EdgePublisher publisher;
    private final Audit audit;
    private final CapabilityRepository capabilities;

    public ConnectorAdminServiceV1(ConnectorRepository repository, AdSourceAdminService adSources, TenantConfigurationServiceV1 configuration,
                                   EdgeClient edge, EdgePublisher publisher, Audit audit, CapabilityRepository capabilities) {
        this.capabilities = capabilities;
        this.repository = repository;
        this.adSources = adSources;
        this.configuration = configuration;
        this.edge = edge;
        this.publisher = publisher;
        this.audit = audit;
    }

    /** Platform admins only; returns the customer project being configured. */
    private ConnectorRepository.Org project(UUID org) {
        if (!repository.isPlatformAdmin(Auth.userId())) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
        var o = repository.organization(org).orElseThrow(() -> ApiException.notFound("Organization"));
        if (!"CUSTOMER".equals(o.kind())) throw ApiException.bad("Connectors are configured for customer companies, not partners.");
        return o;
    }

    /** Checks access, then runs {@code work} as the project. */
    private <T> T asProject(UUID org, Supplier<T> work) {
        project(org);
        return TenantScope.as(org, work);
    }

    public Overview overview(UUID org) {
        var o = project(org);
        return TenantScope.as(org, () -> {
            List<String> keys = repository.readyMediaKeys(org);
            var storage = new Storage("Cloudflare R2 (via the edge Worker)", edge.configured(), edge.publicUrl(), keys.size(),
                    keys.stream().filter(publisher::stored).count());
            // Catalogue = every feature key any plan offers plus enabled global custom features, narrowed to what the
            // platform may grant this project; labels come from the features catalogue.
            Map<String, String> names = TenantScope.bypass(capabilities::global).stream().filter(c -> c.kind().equals("FEATURE"))
                    .collect(Collectors.toMap(Capability::key, c -> c.builtIn() ? c.description() : c.name()));
            String candidates = Stream.concat(configuration.plans().stream().flatMap(p -> p.features().stream()), names.keySet().stream())
                    .distinct().collect(Collectors.joining(","));
            List<FeatureOption> catalog = configuration.grantableFeatures(org, candidates).stream()
                    .map(f -> new FeatureOption(f, names.getOrDefault(f, f))).toList();
            return new Overview(org, o.name(), configuration.grantableFeatures(org, o.features()), catalog, adSources.list(org, true), storage);
        });
    }

    /** Features outside the tenant's plan are rejected rather than silently dropped. */
    public Map<String, List<String>> setFeatures(UUID org, FeaturesRequest r) {
        project(org);
        List<String> on = configuration.requirePlanFeatures(org, r.features());
        repository.setFeatures(org, String.join(",", on));
        audit.log(org, Auth.userId(), "features.update", "organization", org, on.isEmpty() ? "none" : String.join(", ", on));
        return Map.of("features", on);
    }

    // ------------------------------------------------------------------ the project's ad demand waterfall

    public List<IAdSource.Config> adSources(UUID org) { return asProject(org, () -> adSources.list(org, true)); }

    public UUID addAdSource(UUID org, AdSourceAdminService.AdSourceRequest r) {
        return asProject(org, () -> adSources.add(org, Auth.userId(), r, "PLATFORM"));
    }

    public void updateAdSource(UUID org, UUID id, AdSourceAdminService.AdSourceRequest r) {
        asProject(org, () -> { adSources.update(org, Auth.userId(), id, r, null); return null; });
    }

    public void deleteAdSource(UUID org, UUID id) {
        asProject(org, () -> { adSources.delete(org, Auth.userId(), id, null); return null; });
    }

    public List<IAdSource.Config> orderAdSources(UUID org, AdSourceAdminService.OrderRequest r) {
        return asProject(org, () -> adSources.order(org, Auth.userId(), r.ids()));
    }

    public AdSourceAdminService.TagTest testTag(UUID org, AdSourceAdminService.TagTestRequest r) {
        return asProject(org, () -> adSources.test(org, r));
    }
}
