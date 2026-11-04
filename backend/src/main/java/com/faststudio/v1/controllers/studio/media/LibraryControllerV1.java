package com.faststudio.v1.controllers.studio.media;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.LibraryDto.*;
import com.faststudio.v1.services.media.LibraryServiceV1;
import com.faststudio.v1.services.media.MediaSyncServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Project-side video library: status, background sync, "prepare selected" and direct-upload URLs. Provider-neutral paths. */
@RestController
@RequestMapping("/api/orgs/{org}")
public class LibraryControllerV1 {
    private final LibraryServiceV1 library;
    private final MediaSyncServiceV1 sync;

    public LibraryControllerV1(LibraryServiceV1 library, MediaSyncServiceV1 sync) {
        this.library = library;
        this.sync = sync;
    }

    @GetMapping("/library")
    LibraryView library(Tenant t) { return sync.library(t); }

    @PostMapping("/library/sync")
    SyncStatus sync(Tenant t) { return sync.sync(t); }

    @PostMapping("/library/prepare")
    PrepareResult prepare(Tenant t, @Valid @RequestBody PrepareRequest r) { return library.prepare(t, r.assetIds()); }

    @PostMapping("/assets/{id}/upload-url")
    Map<String, String> uploadUrl(Tenant t, @PathVariable UUID id) throws InterruptedException { return library.uploadUrl(t, id); }
}
