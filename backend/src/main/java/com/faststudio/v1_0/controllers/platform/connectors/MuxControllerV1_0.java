package com.faststudio.v1_0.controllers.platform.connectors;

import com.faststudio.v1_0.dto.media.LibraryDto.*;
import com.faststudio.v1_0.services.media.LibraryServiceV1_0;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Platform administration of a project's video library (Mux) account. Credentials are write-only. */
@RestController
@RequestMapping("/api/platform/orgs/{orgId}/connectors/mux")
public class MuxControllerV1_0 {
    private final LibraryServiceV1_0 library;

    public MuxControllerV1_0(LibraryServiceV1_0 library) { this.library = library; }

    @GetMapping
    AccountView account(@PathVariable("orgId") UUID id) { return library.account(id); }

    @PutMapping
    AccountView setAccount(@PathVariable("orgId") UUID id, @Valid @RequestBody AccountRequest r) { return library.saveAccount(id, r); }

    @PostMapping("/sync")
    SyncResult platformSync(@PathVariable("orgId") UUID id) throws InterruptedException { return library.platformSync(id); }
}
