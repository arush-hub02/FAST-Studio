package com.faststudio.v1.controllers.studio.configuration;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.tenancy.CapabilityDto.*;
import com.faststudio.v1.services.tenancy.CapabilityServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** A project's features and permissions: the catalogue it can use, the keys effective for the caller, and its own entries. */
@RestController
@RequestMapping("/api/orgs/{org}/capabilities")
public class ProjectCapabilityControllerV1 {
    private final CapabilityServiceV1 service;

    public ProjectCapabilityControllerV1(CapabilityServiceV1 service) { this.service = service; }

    @GetMapping
    ProjectView view(Tenant t) { return service.project(t); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Capability create(Tenant t, @Valid @RequestBody CreateRequest r) { return service.createProject(t, r); }

    @PutMapping("/{key}")
    Capability update(Tenant t, @PathVariable String key, @Valid @RequestBody UpdateRequest r) { return service.updateProject(t, key, r); }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Tenant t, @PathVariable String key) { service.deleteProject(t, key); }
}
