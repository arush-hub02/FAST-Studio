package com.faststudio.v1.controllers.studio.rights;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.AssetDto.*;
import com.faststudio.v1.services.media.AssetServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Licence windows and territories per asset, and the "can this play here?" check (Rights screen). */
@RestController
@RequestMapping("/api/orgs/{org}")
public class RightsControllerV1 {
    private final AssetServiceV1 assets;

    public RightsControllerV1(AssetServiceV1 assets) { this.assets = assets; }

    @PutMapping("/assets/{id}/rights")
    AssetView putRights(Tenant t, @PathVariable UUID id, @RequestBody RightsRequest r) { return assets.putRights(t, id, r); }

    /** The country is a simulated input, not a geo-IP lookup. */
    @PostMapping("/rights/check")
    CheckResult check(Tenant t, @Valid @RequestBody CheckRequest r) { return assets.check(t, r); }
}
