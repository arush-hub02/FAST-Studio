package com.faststudio.v1.controllers.studio.configuration;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.tenancy.CatalogueDto.ProjectFeatures;
import com.faststudio.v1.services.tenancy.CatalogueServiceV1;
import org.springframework.web.bind.annotation.*;

/** A project's features: what it can link from the global catalogue, what it has linked, and the caller's keys. */
@RestController
@RequestMapping("/api/orgs/{org}/features")
public class ProjectFeatureControllerV1 {
    private final CatalogueServiceV1 service;

    public ProjectFeatureControllerV1(CatalogueServiceV1 service) { this.service = service; }

    @GetMapping
    ProjectFeatures view(Tenant t) { return service.project(t); }

    @PutMapping("/{key}")
    ProjectFeatures link(Tenant t, @PathVariable String key) { return service.link(t, key, true); }

    @DeleteMapping("/{key}")
    ProjectFeatures unlink(Tenant t, @PathVariable String key) { return service.link(t, key, false); }

    @PostMapping("/defaults")
    ProjectFeatures defaults(Tenant t) { return service.linkDefaults(t); }
}
