package com.faststudio.v1_0.controllers.webhook;

import com.faststudio.v1_0.services.media.LibraryServiceV1_0;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Inbound Mux webhooks, one URL per project. Unauthenticated by design: each delivery is verified with that
 * project's webhook secret (unknown project → 404, bad signature → 401).
 */
@RestController
public class MuxWebhookControllerV1_0 {
    private final LibraryServiceV1_0 library;

    public MuxWebhookControllerV1_0(LibraryServiceV1_0 library) { this.library = library; }

    @PostMapping("/hooks/mux/{org}")
    ResponseEntity<Void> webhook(@PathVariable UUID org, @RequestHeader(value = "Mux-Signature", required = false) String signature,
                                 @RequestBody byte[] body) throws GeneralSecurityException {
        return switch (library.webhook(org, signature, body)) {
            case UNKNOWN_PROJECT -> ResponseEntity.notFound().build();
            case BAD_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case ACCEPTED -> ResponseEntity.noContent().build();
        };
    }
}
