package com.faststudio.v1.controllers.platform.tenancy;

import com.faststudio.v1.repositories.team.TenantRoleRepository;
import com.faststudio.v1.services.team.TenantRoleServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Tenant-wide roles: one role in every project of a tenant (WebConfig limits /api/platform/** to platform administrators). */
@RestController
@RequestMapping("/api/platform/tenants/{tenant}/members")
public class TenantRoleControllerV1 {
    private final TenantRoleServiceV1 roles;

    public TenantRoleControllerV1(TenantRoleServiceV1 roles) { this.roles = roles; }

    @GetMapping
    List<TenantRoleRepository.Row> list(@PathVariable UUID tenant) { return roles.list(tenant); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TenantRoleServiceV1.AddResult add(@PathVariable UUID tenant, @Valid @RequestBody TenantRoleServiceV1.AddRequest r) { return roles.add(tenant, r); }

    @PutMapping("/{userId}")
    List<TenantRoleRepository.Row> change(@PathVariable UUID tenant, @PathVariable UUID userId, @Valid @RequestBody TenantRoleServiceV1.RoleRequest r) {
        return roles.change(tenant, userId, r);
    }

    @DeleteMapping("/{userId}")
    List<TenantRoleRepository.Row> remove(@PathVariable UUID tenant, @PathVariable UUID userId) { return roles.remove(tenant, userId); }
}
