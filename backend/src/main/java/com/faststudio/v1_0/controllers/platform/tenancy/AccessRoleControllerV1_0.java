package com.faststudio.v1_0.controllers.platform.tenancy;

import com.faststudio.v1_0.services.tenancy.AccessRoleServiceV1_0;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Platform administration of custom organization roles (customer and partner types). */
@RestController
@RequestMapping("/api/platform/access-roles")
public class AccessRoleControllerV1_0 {
    private final AccessRoleServiceV1_0 service;

    public AccessRoleControllerV1_0(AccessRoleServiceV1_0 service) { this.service = service; }

    /** All custom roles plus the permission names a role may include. */
    @GetMapping
    Map<String, Object> roles() { return Map.of("roles", service.roles(), "permissions", service.catalog()); }

    @PutMapping
    Map<String, Object> save(@Valid @RequestBody AccessRoleServiceV1_0.Role role) {
        service.save(role);
        return roles();
    }
}
