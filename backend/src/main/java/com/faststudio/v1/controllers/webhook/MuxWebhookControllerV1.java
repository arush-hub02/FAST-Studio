package com.faststudio.v1.controllers.webhook;

import com.faststudio.v1.services.media.LibraryServiceV1;
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
public class MuxWebhookControllerV1 {
    private final LibraryServiceV1 library;

    public MuxWebhookControllerV1(LibraryServiceV1 library) { this.library = library; }

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
