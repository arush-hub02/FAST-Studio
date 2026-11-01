package com.faststudio.v1.controllers.platform.connectors;

import com.faststudio.v1.dto.media.LibraryDto.*;
import com.faststudio.v1.services.media.MediaConnectionService;
import com.faststudio.v1.services.media.MediaSyncServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Platform administration of a project's video library (Mux) account: credentials (write-only), health check and sync. */
@RestController
@RequestMapping("/api/platform/orgs/{orgId}/connectors/mux")
public class MuxControllerV1 {
    private final MediaConnectionService connections;
    private final MediaSyncServiceV1 sync;

    public MuxControllerV1(MediaConnectionService connections, MediaSyncServiceV1 sync) {
        this.connections = connections;
        this.sync = sync;
    }

    @GetMapping
    AccountView account(@PathVariable("orgId") UUID id) { return connections.view(id); }

    @PutMapping
    AccountView setAccount(@PathVariable("orgId") UUID id, @Valid @RequestBody AccountRequest r) { return connections.save(id, r); }

    @PostMapping("/check")
    AccountView check(@PathVariable("orgId") UUID id) throws InterruptedException { return connections.check(id); }

    @GetMapping("/sync")
    SyncStatus syncStatus(@PathVariable("orgId") UUID id) { return sync.platformStatus(id); }

    @PostMapping("/sync")
    SyncStatus platformSync(@PathVariable("orgId") UUID id) { return sync.platformSync(id); }
}
