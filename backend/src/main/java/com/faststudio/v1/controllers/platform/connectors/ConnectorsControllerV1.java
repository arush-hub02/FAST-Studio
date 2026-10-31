package com.faststudio.v1.controllers.platform.connectors;

import com.faststudio.v1.dto.connectors.ConnectorsDto.FeaturesRequest;
import com.faststudio.v1.dto.connectors.ConnectorsDto.Overview;
import com.faststudio.v1.services.ads.AdSourceAdminService;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.connectors.ConnectorAdminServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform administrator's Connectors page for one project. Content-platform connectors (e.g. Mux) have their own
 * endpoints under /api/platform/orgs/{orgId}/connectors/. Rules live in {@link ConnectorAdminServiceV1}.
 */
@RestController
@RequestMapping("/api/platform/orgs/{orgId}")
public class ConnectorsControllerV1 {
    private final ConnectorAdminServiceV1 connectors;

    public ConnectorsControllerV1(ConnectorAdminServiceV1 connectors) { this.connectors = connectors; }

    @GetMapping("/connectors")
    Overview overview(@PathVariable UUID orgId) { return connectors.overview(orgId); }

    @PutMapping("/features")
    Map<String, List<String>> features(@PathVariable UUID orgId, @Valid @RequestBody FeaturesRequest r) { return connectors.setFeatures(orgId, r); }

    @GetMapping("/ad-sources")
    List<IAdSource.Config> sources(@PathVariable UUID orgId) { return connectors.adSources(orgId); }

    @PostMapping("/ad-sources")
    Map<String, UUID> add(@PathVariable UUID orgId, @Valid @RequestBody AdSourceAdminService.AdSourceRequest r) {
        return Map.of("id", connectors.addAdSource(orgId, r));
    }

    @PutMapping("/ad-sources/{id}")
    Map<String, UUID> update(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody AdSourceAdminService.AdSourceRequest r) {
        connectors.updateAdSource(orgId, id, r);
        return Map.of("id", id);
    }

    @DeleteMapping("/ad-sources/{id}")
    Map<String, UUID> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        connectors.deleteAdSource(orgId, id);
        return Map.of("id", id);
    }

    @PostMapping("/ad-sources/order")
    List<IAdSource.Config> order(@PathVariable UUID orgId, @Valid @RequestBody AdSourceAdminService.OrderRequest r) {
        return connectors.orderAdSources(orgId, r);
    }

    @PostMapping("/ad-sources/test")
    AdSourceAdminService.TagTest test(@PathVariable UUID orgId, @Valid @RequestBody AdSourceAdminService.TagTestRequest r) {
        return connectors.testTag(orgId, r);
    }
}
