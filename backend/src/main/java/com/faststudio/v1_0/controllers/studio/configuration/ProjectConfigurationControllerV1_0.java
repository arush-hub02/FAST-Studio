package com.faststudio.v1_0.controllers.studio.configuration;

import com.faststudio.v1_0.dto.tenancy.TenantConfiguration.Enable;
import com.faststudio.v1_0.dto.tenancy.TenantConfiguration.Value;

import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.repositories.tenancy.TenantConfigurationRepository;
import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Tenant and project configuration as seen from inside a project ({org} = the project slug or ID).
 *
 * <p>{@code /configuration} is the project scope (overrides, connector opt-in/out; project OWNER/ADMIN).
 * {@code /tenant-settings} is the tenant-wide scope (defaults for every project; tenant OWNER).
 * The service enforces the role rules; TEAM_MANAGE is checked here first so custom roles that removed it are excluded.
 */
@RestController
@RequestMapping("/api/orgs/{org}")
public class ProjectConfigurationControllerV1_0 {
    private final TenantConfigurationServiceV1_0 service;
    private final TenantConfigurationRepository repository;

    public ProjectConfigurationControllerV1_0(TenantConfigurationServiceV1_0 service, TenantConfigurationRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Cookie", "Authorization").body(value);
    }

    @GetMapping("/configuration")
    Object configuration(Tenant context) {
        return noStore(service.settings(repository.tenant(context.orgId()), context.orgId()));
    }

    @PutMapping("/configuration/properties/{key}")
    Object projectValue(Tenant context, @PathVariable String key, @RequestBody Value request) {
        context.require(Tenant.Perm.TEAM_MANAGE);
        service.setValue(repository.tenant(context.orgId()), context.orgId(), key, request);
        return configuration(context);
    }

    @PutMapping("/configuration/connectors/{key}")
    Object projectConnector(Tenant context, @PathVariable String key, @RequestBody Enable request) {
        context.require(Tenant.Perm.TEAM_MANAGE);
        service.setConnector(repository.tenant(context.orgId()), context.orgId(), key, request);
        return configuration(context);
    }

    @GetMapping("/tenant-settings")
    Object tenantSettings(Tenant context) {
        return noStore(service.settings(repository.tenant(context.orgId()), null));
    }

    @PutMapping("/tenant-settings/properties/{key}")
    Object tenantValue(Tenant context, @PathVariable String key, @RequestBody Value request) {
        context.require(Tenant.Perm.TEAM_MANAGE);
        service.setValue(repository.tenant(context.orgId()), null, key, request);
        return tenantSettings(context);
    }
}
