package com.faststudio.connectors;

import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;

import com.faststudio.ads.AdSource;
import com.faststudio.ads.AdSourceAdmin;
import com.faststudio.core.*;
import com.faststudio.edge.EdgeClient;
import com.faststudio.edge.EdgePublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Platform administrator's Connectors page: per company, the ad demand waterfall (direct-sold plus external ad
 * servers), media storage status, and the feature switches that decide what the company itself sees of them.
 * Content-platform connectors (e.g. Mux) add their own endpoints under /api/platform/orgs/{orgId}/connectors/.
 * Companies never configure connectors: credentials and tag URLs stay with the platform (HANDOVER §4.5).
 */
@RestController
@RequestMapping("/api/platform/orgs/{orgId}")
public class ConnectorsController {
    private final Data data;
    private final Audit audit;
    private final AdSourceAdmin adSources;
    private final EdgeClient edge;
    private final EdgePublisher publisher;
    private final TenantConfigurationServiceV1_0 configuration;

    public ConnectorsController(Data data, Audit audit, AdSourceAdmin adSources, EdgeClient edge, EdgePublisher publisher,
                                TenantConfigurationServiceV1_0 configuration) {
        this.data = data;
        this.audit = audit;
        this.adSources = adSources;
        this.edge = edge;
        this.publisher = publisher;
        this.configuration=configuration;
    }

    record Org(UUID id, String name, String kind, String features) {}

    /** Platform admins only; returns the customer organization being configured. */
    private Org org(UUID orgId) {
        boolean admin = data.db.sql("select platform_admin from app_user where id = :id").param("id", Auth.userId()).query(Boolean.class).single();
        if (!admin) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
        Org o = data.db.sql("select id, name, kind, features from organization where id = :id").param("id", orgId).query(Org.class).optional()
                .orElseThrow(() -> ApiException.notFound("Organization"));
        if (!"CUSTOMER".equals(o.kind())) throw ApiException.bad("Connectors are configured for customer companies, not partners.");
        return o;
    }

    public record FeatureOption(String key, String label) {}

    public record Storage(String provider, boolean configured, String publicUrl, long videosReady, long videosOnEdge) {}

    public record Overview(UUID orgId, String orgName, List<String> features, List<FeatureOption> featureCatalog,
                           List<AdSource.Config> adSources, Storage storage) {}

    @GetMapping("/connectors")
    Overview overview(@PathVariable UUID orgId) {
        Org o = org(orgId);
        return TenantScope.as(orgId, () -> {
            List<String> keys = data.db.sql("select media_key from asset where org_id = :o and status = 'READY' and media_key is not null")
                    .param("o", orgId).query(String.class).list();
            var storage = new Storage("Cloudflare R2 (via the edge Worker)", edge.configured(), edge.publicUrl(), keys.size(),
                    keys.stream().filter(publisher::stored).count());
            return new Overview(orgId, o.name(), configuration.effectiveFeatures(orgId,o.features()),
                    configuration.effectiveFeatures(orgId,configuration.plans().stream().flatMap(p->p.features().stream()).distinct().collect(java.util.stream.Collectors.joining(",")))
                            .stream().map(f->new FeatureOption(f,Arrays.stream(Feature.values()).filter(v->v.name().equals(f)).map(v->v.label).findFirst().orElse(f))).toList(), adSources.list(orgId, true), storage);
        });
    }

    public record FeaturesRequest(@NotNull List<String> features) {}

    @PutMapping("/features")
    Map<String, List<String>> features(@PathVariable UUID orgId, @Valid @RequestBody FeaturesRequest r) {
        org(orgId);
        // Features outside the tenant's plan are rejected rather than silently dropped.
        List<String> on = configuration.requirePlanFeatures(orgId, r.features());
        data.db.sql("update organization set features = :f where id = :id").param("f", String.join(",", on)).param("id", orgId).update();
        audit.log(orgId, Auth.userId(), "features.update", "organization", orgId, on.isEmpty() ? "none" : String.join(", ", on));
        return Map.of("features", on);
    }

    // ------------------------------------------------------------------ the company's ad demand waterfall

    @GetMapping("/ad-sources")
    List<AdSource.Config> sources(@PathVariable UUID orgId) {
        org(orgId);
        return TenantScope.as(orgId, () -> adSources.list(orgId, true));
    }

    @PostMapping("/ad-sources")
    Map<String, UUID> add(@PathVariable UUID orgId, @Valid @RequestBody AdSourceAdmin.AdSourceRequest r) {
        org(orgId);
        return Map.of("id", TenantScope.as(orgId, () -> adSources.add(orgId, Auth.userId(), r, "PLATFORM")));
    }

    @PutMapping("/ad-sources/{id}")
    Map<String, UUID> update(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody AdSourceAdmin.AdSourceRequest r) {
        org(orgId);
        TenantScope.as(orgId, () -> { adSources.update(orgId, Auth.userId(), id, r, null); return null; });
        return Map.of("id", id);
    }

    @DeleteMapping("/ad-sources/{id}")
    Map<String, UUID> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        org(orgId);
        TenantScope.as(orgId, () -> { adSources.delete(orgId, Auth.userId(), id, null); return null; });
        return Map.of("id", id);
    }

    @PostMapping("/ad-sources/order")
    List<AdSource.Config> order(@PathVariable UUID orgId, @Valid @RequestBody AdSourceAdmin.OrderRequest r) {
        org(orgId);
        return TenantScope.as(orgId, () -> adSources.order(orgId, Auth.userId(), r.ids()));
    }

    @PostMapping("/ad-sources/test")
    AdSourceAdmin.TagTest test(@PathVariable UUID orgId, @Valid @RequestBody AdSourceAdmin.TagTestRequest r) {
        org(orgId);
        return TenantScope.as(orgId, () -> adSources.test(orgId, r));
    }
}
