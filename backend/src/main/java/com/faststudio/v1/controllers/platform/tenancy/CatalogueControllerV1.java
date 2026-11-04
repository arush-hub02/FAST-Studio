package com.faststudio.v1.controllers.platform.tenancy;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.dto.tenancy.CatalogueDto.*;
import com.faststudio.v1.services.tenancy.CatalogueServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Platform administration of the global catalogue: modules, feature groups, features and permissions. */
@RestController
@RequestMapping("/api/platform")
public class CatalogueControllerV1 {
    private final CatalogueServiceV1 service;

    public CatalogueControllerV1(CatalogueServiceV1 service) { this.service = service; }

    @GetMapping("/catalogue")
    Catalogue catalogue() { return service.catalogue(); }

    @PostMapping("/modules")
    @ResponseStatus(HttpStatus.CREATED)
    ModuleEntry createModule(@Valid @RequestBody ModuleRequest r) { return service.saveModule(r, true); }

    @PutMapping("/modules/{key}")
    ModuleEntry updateModule(@PathVariable String key, @Valid @RequestBody ModuleRequest r) { same(key, r.key()); return service.saveModule(r, false); }

    @DeleteMapping("/modules/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteModule(@PathVariable String key) { service.deleteModule(key); }

    @PostMapping("/feature-groups")
    @ResponseStatus(HttpStatus.CREATED)
    Group createGroup(@Valid @RequestBody GroupRequest r) { return service.saveGroup(r, true); }

    @PutMapping("/feature-groups/{key}")
    Group updateGroup(@PathVariable String key, @Valid @RequestBody GroupRequest r) { same(key, r.key()); return service.saveGroup(r, false); }

    @DeleteMapping("/feature-groups/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteGroup(@PathVariable String key) { service.deleteGroup(key); }

    @PostMapping("/features")
    @ResponseStatus(HttpStatus.CREATED)
    Feature createFeature(@Valid @RequestBody FeatureRequest r) { return service.saveFeature(r, true); }

    @PutMapping("/features/{key}")
    Feature updateFeature(@PathVariable String key, @Valid @RequestBody FeatureRequest r) { same(key, r.key()); return service.saveFeature(r, false); }

    @DeleteMapping("/features/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFeature(@PathVariable String key) { service.deleteEntry(key, "FEATURE"); }

    @PostMapping("/permissions")
    @ResponseStatus(HttpStatus.CREATED)
    Permission createPermission(@Valid @RequestBody PermissionRequest r) { return service.savePermission(r, true); }

    @PutMapping("/permissions/{key}")
    Permission updatePermission(@PathVariable String key, @Valid @RequestBody PermissionRequest r) { same(key, r.key()); return service.savePermission(r, false); }

    @DeleteMapping("/permissions/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePermission(@PathVariable String key) { service.deleteEntry(key, "PERMISSION"); }

    /** Identifiers never change: the body's key must be the one in the path. */
    private static void same(String path, String body) {
        if (!path.equals(body)) throw ApiException.bad("The identifier can't change.");
    }
}
