package com.faststudio.v1_0.controllers.studio.media;

import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.dto.media.AssetDto.*;
import com.faststudio.v1_0.services.media.AssetServiceV1_0;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Media library of the project (Library screen). Rules live in {@link AssetServiceV1_0}. */
@RestController
@RequestMapping("/api/orgs/{org}/assets")
public class AssetControllerV1_0 {
    private final AssetServiceV1_0 assets;

    public AssetControllerV1_0(AssetServiceV1_0 assets) { this.assets = assets; }

    @GetMapping
    List<AssetView> list(Tenant t, @RequestParam(required = false) String q, @RequestParam(required = false) String kind,
                         @RequestParam(defaultValue = "false") boolean archived) {
        return assets.list(t, q, kind, archived);
    }

    @GetMapping("/{id}")
    AssetView get(Tenant t, @PathVariable UUID id) { return assets.get(t, id); }

    @PostMapping
    AssetView create(Tenant t, @Valid @RequestBody CreateRequest r) { return assets.create(t, r); }

    @PostMapping(value = "/{id}/upload", consumes = "multipart/form-data")
    AssetView upload(Tenant t, @PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        return assets.upload(t, id, file);
    }

    @PostMapping("/{id}/retry")
    AssetView retry(Tenant t, @PathVariable UUID id) { return assets.retry(t, id); }

    @PatchMapping("/{id}")
    AssetView update(Tenant t, @PathVariable UUID id, @Valid @RequestBody UpdateRequest r) { return assets.update(t, id, r); }

    @PostMapping("/{id}/archive")
    AssetView archive(Tenant t, @PathVariable UUID id) { return assets.archive(t, id); }
}
