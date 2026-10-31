package com.faststudio.v1.controllers.platform.connectors;

import com.faststudio.v1.dto.media.LibraryDto.*;
import com.faststudio.v1.services.media.LibraryServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Platform administration of a project's video library (Mux) account. Credentials are write-only. */
@RestController
@RequestMapping("/api/platform/orgs/{orgId}/connectors/mux")
public class MuxControllerV1 {
    private final LibraryServiceV1 library;

    public MuxControllerV1(LibraryServiceV1 library) { this.library = library; }

    @GetMapping
    AccountView account(@PathVariable("orgId") UUID id) { return library.account(id); }

    @PutMapping
    AccountView setAccount(@PathVariable("orgId") UUID id, @Valid @RequestBody AccountRequest r) { return library.saveAccount(id, r); }

    @PostMapping("/sync")
    SyncResult platformSync(@PathVariable("orgId") UUID id) throws InterruptedException { return library.platformSync(id); }
}
