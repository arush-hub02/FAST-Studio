package com.faststudio.v1.controllers.studio.team;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.services.tenancy.AccessRoleServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** A project's own roles (TEAM_MANAGE): global roles are listed read-only beside them. */
@RestController
@RequestMapping("/api/orgs/{org}/roles")
public class ProjectRoleControllerV1 {
    private final AccessRoleServiceV1 service;

    public ProjectRoleControllerV1(AccessRoleServiceV1 service) { this.service = service; }

    @GetMapping
    AccessRoleServiceV1.ProjectRoles list(Tenant t) { return service.projectRoles(t); }

    @PutMapping
    AccessRoleServiceV1.ProjectRoles save(Tenant t, @Valid @RequestBody AccessRoleServiceV1.Role role) { return service.saveProjectRole(t, role); }

    @DeleteMapping("/{key}")
    AccessRoleServiceV1.ProjectRoles delete(Tenant t, @PathVariable String key) { return service.deleteProjectRole(t, key); }
}
