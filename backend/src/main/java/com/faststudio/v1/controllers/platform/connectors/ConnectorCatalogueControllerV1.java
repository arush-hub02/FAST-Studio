package com.faststudio.v1.controllers.platform.connectors;

import com.faststudio.v1.dto.connectors.ConnectorCatalogueDto.*;
import com.faststudio.v1.services.connectors.ConnectorCatalogueServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Platform side of the connector marketplace: categories, connector details and fields, platform-level connections
 * (R2) and any project's connections (tenant page). Rules live in {@link ConnectorCatalogueServiceV1}.
 */
@RestController
@RequestMapping("/api/platform")
public class ConnectorCatalogueControllerV1 {
    private final ConnectorCatalogueServiceV1 service;

    public ConnectorCatalogueControllerV1(ConnectorCatalogueServiceV1 service) { this.service = service; }

    @GetMapping("/connector-catalogue")
    Catalogue catalogue() { return service.catalogue(); }

    @PostMapping("/connector-categories")
    @ResponseStatus(HttpStatus.CREATED)
    Category addCategory(@Valid @RequestBody CategoryRequest r) { return service.saveCategory(r, true); }

    @PutMapping("/connector-categories/{key}")
    Category updateCategory(@PathVariable String key, @Valid @RequestBody CategoryRequest r) {
        return service.saveCategory(new CategoryRequest(key, r.name(), r.description(), r.position()), false);
    }

    @DeleteMapping("/connector-categories/{key}")
    Map<String, String> deleteCategory(@PathVariable String key) {
        service.deleteCategory(key);
        return Map.of("key", key);
    }

    @PutMapping("/connector-definitions/{key}/details")
    Connector details(@PathVariable String key, @Valid @RequestBody DetailsRequest r) { return service.saveDetails(key, r); }

    @PutMapping("/connector-definitions/{key}/properties/{prop}")
    Connector saveProperty(@PathVariable String key, @PathVariable String prop, @Valid @RequestBody PropertyRequest r) {
        return service.saveProperty(key, new PropertyRequest(prop, r.label(), r.description(), r.valueType(), r.options(), r.defaultValue(),
                r.position(), r.required(), r.encrypted(), r.scope()));
    }

    @DeleteMapping("/connector-definitions/{key}/properties/{prop}")
    Connector deleteProperty(@PathVariable String key, @PathVariable String prop) { return service.deleteProperty(key, prop); }

    // platform-level connections (one set of values for the whole platform)

    @GetMapping("/connector-instances")
    Marketplace platform() { return service.forPlatform(); }

    @PostMapping("/connector-instances/{key}")
    ConnectorState connectPlatform(@PathVariable String key, @Valid @RequestBody InstanceRequest r) { return service.saveInstance(null, null, key, null, r); }

    @PutMapping("/connector-instances/{key}/{id}")
    ConnectorState updatePlatform(@PathVariable String key, @PathVariable UUID id, @Valid @RequestBody InstanceRequest r) {
        return service.saveInstance(null, null, key, id, r);
    }

    @DeleteMapping("/connector-instances/{key}/{id}")
    ConnectorState deletePlatform(@PathVariable String key, @PathVariable UUID id) { return service.deleteInstance(null, null, key, id); }

    // one project's connections, as the platform manages them

    @GetMapping("/orgs/{orgId}/connector-instances")
    Marketplace project(@PathVariable UUID orgId) { return service.forPlatformProject(orgId); }

    @PostMapping("/orgs/{orgId}/connector-instances/{key}")
    ConnectorState connect(@PathVariable UUID orgId, @PathVariable String key, @Valid @RequestBody InstanceRequest r) {
        return service.saveInstance(null, orgId, key, null, r);
    }

    @PutMapping("/orgs/{orgId}/connector-instances/{key}/{id}")
    ConnectorState update(@PathVariable UUID orgId, @PathVariable String key, @PathVariable UUID id, @Valid @RequestBody InstanceRequest r) {
        return service.saveInstance(null, orgId, key, id, r);
    }

    @DeleteMapping("/orgs/{orgId}/connector-instances/{key}/{id}")
    ConnectorState delete(@PathVariable UUID orgId, @PathVariable String key, @PathVariable UUID id) { return service.deleteInstance(null, orgId, key, id); }
}
