package com.faststudio.v1.controllers.platform.tenancy;

import com.faststudio.v1.dto.tenancy.CatalogueDto.ProjectFeatures;
import com.faststudio.v1.services.media.MediaConnectionService;
import com.faststudio.v1.services.tenancy.AccessRoleServiceV1;
import com.faststudio.v1.services.tenancy.CatalogueServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Platform administration of one customer project's features and roles (the tenant detail page). Platform
 * administrators aren't project members, so these mirror the project's own /features and /roles routes.
 */
@RestController
@RequestMapping("/api/platform/orgs/{orgId}")
public class PlatformProjectAccessControllerV1 {
    private final CatalogueServiceV1 catalogue;
    private final AccessRoleServiceV1 roles;
    private final MediaConnectionService projects;

    public PlatformProjectAccessControllerV1(CatalogueServiceV1 catalogue, AccessRoleServiceV1 roles, MediaConnectionService projects) {
        this.catalogue = catalogue;
        this.roles = roles;
        this.projects = projects;
    }

    @GetMapping("/project-features")
    ProjectFeatures features(@PathVariable UUID orgId) { projects.requireCustomer(orgId); return catalogue.platformProject(orgId); }

    @PutMapping("/project-features/{key}")
    ProjectFeatures link(@PathVariable UUID orgId, @PathVariable String key) { projects.requireCustomer(orgId); return catalogue.platformLink(orgId, key, true); }

    @DeleteMapping("/project-features/{key}")
    ProjectFeatures unlink(@PathVariable UUID orgId, @PathVariable String key) { projects.requireCustomer(orgId); return catalogue.platformLink(orgId, key, false); }

    @GetMapping("/roles")
    AccessRoleServiceV1.ProjectRoles roles(@PathVariable UUID orgId) { projects.requireCustomer(orgId); return roles.platformProjectRoles(orgId); }

    @PutMapping("/roles")
    AccessRoleServiceV1.ProjectRoles saveRole(@PathVariable UUID orgId, @Valid @RequestBody AccessRoleServiceV1.Role role) {
        projects.requireCustomer(orgId);
        return roles.platformSaveProjectRole(orgId, role);
    }

    @DeleteMapping("/roles/{key}")
    AccessRoleServiceV1.ProjectRoles deleteRole(@PathVariable UUID orgId, @PathVariable String key) {
        projects.requireCustomer(orgId);
        return roles.platformDeleteProjectRole(orgId, key);
    }
}
