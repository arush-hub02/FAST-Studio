package com.faststudio.v1.controllers.studio.configuration;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.connectors.ConnectorCatalogueDto.*;
import com.faststudio.v1.services.connectors.ConnectorCatalogueServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** A project's connector marketplace: what members may see, and connecting the ones whose rule lets the project do it. */
@RestController
@RequestMapping("/api/orgs/{org}/connectors")
public class ProjectConnectorControllerV1 {
    private final ConnectorCatalogueServiceV1 service;

    public ProjectConnectorControllerV1(ConnectorCatalogueServiceV1 service) { this.service = service; }

    @GetMapping
    Marketplace view(Tenant t) { return service.forProject(t); }

    @PostMapping("/{key}")
    ConnectorState connect(Tenant t, @PathVariable String key, @Valid @RequestBody InstanceRequest r) { return service.saveInstance(t, t.orgId(), key, null, r); }

    @PutMapping("/{key}/{id}")
    ConnectorState update(Tenant t, @PathVariable String key, @PathVariable UUID id, @Valid @RequestBody InstanceRequest r) {
        return service.saveInstance(t, t.orgId(), key, id, r);
    }

    @DeleteMapping("/{key}/{id}")
    ConnectorState delete(Tenant t, @PathVariable String key, @PathVariable UUID id) { return service.deleteInstance(t, t.orgId(), key, id); }
}
