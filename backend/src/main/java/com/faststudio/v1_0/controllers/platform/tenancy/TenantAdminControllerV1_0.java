package com.faststudio.v1_0.controllers.platform.tenancy;

import com.faststudio.v1_0.dto.tenancy.TenantConfiguration.*;

import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Platform administration of the tenant model (WebConfig limits /api/platform/** to platform admins;
 * the service checks again for every write).
 *
 * <ul>
 *   <li>Global: subscription plans, connector definitions, custom property definitions and defaults.</li>
 *   <li>Per tenant: subscription, status, projects, tenant property values, connector grants.</li>
 *   <li>Per project: property overrides and connector opt-in/out.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/platform")
public class TenantAdminControllerV1_0 {
    private final TenantConfigurationServiceV1_0 service;

    public TenantAdminControllerV1_0(TenantConfigurationServiceV1_0 service) { this.service = service; }

    /** Configuration may include tenant data; never let a shared cache store it. */
    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Cookie", "Authorization").body(value);
    }

    // ------------------------------------------------------------------ global definitions

    @GetMapping("/plans")
    Object plans() { return noStore(service.plans()); }

    @PutMapping("/plans")
    Object savePlan(@Valid @RequestBody Plan request) { service.savePlan(request); return plans(); }

    @GetMapping("/connector-definitions")
    Object connectors() { return noStore(service.connectors()); }

    @PutMapping("/connector-definitions")
    Object saveConnector(@Valid @RequestBody Connector request) { service.saveConnector(request); return connectors(); }

    @GetMapping("/property-definitions")
    Object properties() { return noStore(service.definitions()); }

    @PutMapping("/property-definitions")
    Object saveProperty(@Valid @RequestBody Property request) { service.saveProperty(request); return properties(); }

    @DeleteMapping("/property-definitions/{key}/default")
    Object clearDefault(@PathVariable String key) { service.clearDefault(key); return properties(); }

    // ------------------------------------------------------------------ tenants

    @GetMapping("/tenants")
    Object tenants() { return noStore(service.accounts()); }

    @GetMapping("/tenants/{tenant}/settings")
    Object settings(@PathVariable UUID tenant) { return noStore(service.settings(tenant, null)); }

    @GetMapping("/tenants/{tenant}/owners")
    Object owners(@PathVariable UUID tenant) { return noStore(service.owners(tenant)); }

    @PutMapping("/tenants/{tenant}/subscription")
    Object subscription(@PathVariable UUID tenant, @Valid @RequestBody Subscription request) {
        service.subscription(tenant, request);
        return settings(tenant);
    }

    @PostMapping("/tenants/{tenant}/status")
    Object status(@PathVariable UUID tenant, @RequestBody Map<String, String> request) {
        service.status(tenant, request.get("status"));
        return settings(tenant);
    }

    @PutMapping("/tenants/{tenant}/properties/{key}")
    Object tenantValue(@PathVariable UUID tenant, @PathVariable String key, @RequestBody Value request) {
        service.setValue(tenant, null, key, request);
        return settings(tenant);
    }

    @PutMapping("/tenants/{tenant}/connectors/{key}")
    Object tenantConnector(@PathVariable UUID tenant, @PathVariable String key, @RequestBody Enable request) {
        service.setConnector(tenant, null, key, request);
        return settings(tenant);
    }

    // ------------------------------------------------------------------ projects of a tenant

    @PostMapping("/tenants/{tenant}/projects")
    Object createProject(@PathVariable UUID tenant, @Valid @RequestBody CreateProject request) {
        return noStore(service.createProject(tenant, request));
    }

    @GetMapping("/tenants/{tenant}/projects/{project}/settings")
    Object projectSettings(@PathVariable UUID tenant, @PathVariable UUID project) { return noStore(service.settings(tenant, project)); }

    @PutMapping("/tenants/{tenant}/projects/{project}/properties/{key}")
    Object projectValue(@PathVariable UUID tenant, @PathVariable UUID project, @PathVariable String key, @RequestBody Value request) {
        service.setValue(tenant, project, key, request);
        return projectSettings(tenant, project);
    }

    @PutMapping("/tenants/{tenant}/projects/{project}/connectors/{key}")
    Object projectConnector(@PathVariable UUID tenant, @PathVariable UUID project, @PathVariable String key, @RequestBody Enable request) {
        service.setConnector(tenant, project, key, request);
        return projectSettings(tenant, project);
    }
}
