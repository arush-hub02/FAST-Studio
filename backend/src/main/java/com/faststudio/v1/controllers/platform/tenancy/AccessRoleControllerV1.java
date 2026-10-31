package com.faststudio.v1.controllers.platform.tenancy;

import com.faststudio.v1.services.tenancy.AccessRoleServiceV1;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Platform administration of custom organization roles (customer and partner types). */
@RestController
@RequestMapping("/api/platform/access-roles")
public class AccessRoleControllerV1 {
    private final AccessRoleServiceV1 service;

    public AccessRoleControllerV1(AccessRoleServiceV1 service) { this.service = service; }

    /** All custom roles plus the permission names a role may include. */
    @GetMapping
    Map<String, Object> roles() { return Map.of("roles", service.roles(), "permissions", service.catalog()); }

    @PutMapping
    Map<String, Object> save(@Valid @RequestBody AccessRoleServiceV1.Role role) {
        service.save(role);
        return roles();
    }
}
