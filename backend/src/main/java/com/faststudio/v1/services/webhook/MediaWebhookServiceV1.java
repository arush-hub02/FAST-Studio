package com.faststudio.v1.services.webhook;

import com.faststudio.v1.services.media.LibraryServiceV1;
import com.faststudio.v1.services.media.MuxConnectionService;
import com.faststudio.v1.util.mux.MuxClientService;
import com.faststudio.v1.util.mux.MuxWebhookVerifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Inbound video-library webhooks. Each delivery is verified on its raw bytes with that project's own secret before it
 * is read; accepted asset events take the same idempotent path as a library sync, so redelivery is harmless.
 * ponytail: Mux is the only provider; add a parser per provider here when a second one sends webhooks.
 */
@Service
public class MediaWebhookServiceV1 {
    static final Set<String> MUX_EVENTS = Set.of("video.asset.created", "video.asset.ready", "video.asset.errored");

    /** Outcome of a delivery, mapped to an HTTP status by the controller. */
    public enum Result { UNKNOWN_PROJECT, BAD_SIGNATURE, ACCEPTED }

    private final MuxConnectionService connections;
    private final LibraryServiceV1 library;
    private final JsonMapper json;

    public MediaWebhookServiceV1(MuxConnectionService connections, LibraryServiceV1 library, JsonMapper json) {
        this.connections = connections;
        this.library = library;
        this.json = json;
    }

    public Result mux(UUID org, String signature, byte[] body) throws GeneralSecurityException {
        MuxClientService.Account a = connections.account(org);
        if (a == null || a.webhookSecret() == null) return Result.UNKNOWN_PROJECT;
        if (!MuxWebhookVerifier.signed(a.webhookSecret(), signature, body, Instant.now())) return Result.BAD_SIGNATURE;
        JsonNode event = json.readTree(body);
        MuxClientService.Asset x = MUX_EVENTS.contains(event.path("type").asString("")) ? MuxClientService.Asset.of(event.path("data")) : null;
        if (x != null && a.includes(x)) library.apply(org, x);
        return Result.ACCEPTED;
    }
}
