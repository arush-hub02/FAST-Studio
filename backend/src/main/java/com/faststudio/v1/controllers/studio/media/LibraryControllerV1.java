package com.faststudio.v1.controllers.studio.media;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.LibraryDto.SyncResult;
import com.faststudio.v1.services.media.LibraryServiceV1;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Project-side video library: sync from the connected library and direct-upload URLs. Provider-neutral paths. */
@RestController
@RequestMapping("/api/orgs/{org}")
public class LibraryControllerV1 {
    private final LibraryServiceV1 library;

    public LibraryControllerV1(LibraryServiceV1 library) { this.library = library; }

    @PostMapping("/library/sync")
    SyncResult sync(Tenant t) throws InterruptedException { return library.sync(t); }

    @PostMapping("/assets/{id}/upload-url")
    Map<String, String> uploadUrl(Tenant t, @PathVariable UUID id) throws InterruptedException { return library.uploadUrl(t, id); }
}
