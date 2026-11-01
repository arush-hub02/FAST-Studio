package com.faststudio.v1.services.webhook;

import com.faststudio.v1.services.media.LibraryServiceV1;
import com.faststudio.v1.services.media.MediaConnectionService;
import com.faststudio.v1.util.mediaProvider.IMediaProvider.RemoteAsset;
import com.faststudio.v1.util.mux.MuxClientService;
import com.faststudio.v1.util.mux.MuxProvider;
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
 * A deleted video is only flagged on its source; the local video and any prepared media stay.
 * ponytail: Mux is the only provider sending webhooks; add a parser per provider here when a second one does.
 */
@Service
public class MediaWebhookServiceV1 {
    static final Set<String> MUX_EVENTS = Set.of("video.asset.created", "video.asset.ready", "video.asset.errored", "video.asset.deleted");

    /** Outcome of a delivery, mapped to an HTTP status by the controller. */
    public enum Result { UNKNOWN_PROJECT, BAD_SIGNATURE, ACCEPTED }

    private final MediaConnectionService connections;
    private final LibraryServiceV1 library;
    private final JsonMapper json;

    public MediaWebhookServiceV1(MediaConnectionService connections, LibraryServiceV1 library, JsonMapper json) {
        this.connections = connections;
        this.library = library;
        this.json = json;
    }

    public Result mux(UUID org, String signature, byte[] body) throws GeneralSecurityException {
        MuxClientService.Account a = connections.account(org);
        if (a == null || a.webhookSecret() == null) return Result.UNKNOWN_PROJECT;
        if (!MuxWebhookVerifier.signed(a.webhookSecret(), signature, body, Instant.now())) return Result.BAD_SIGNATURE;
        JsonNode event = json.readTree(body);
        String type = event.path("type").asString("");
        MuxClientService.Asset x = MUX_EVENTS.contains(type) ? MuxClientService.Asset.of(event.path("data")) : null;
        if (x != null && a.includes(x)) {
            RemoteAsset r = MuxProvider.normalize(x);
            library.apply(org, MuxProvider.KEY, type.equals("video.asset.deleted") ? r.withStatus("DELETED") : r);
        }
        return Result.ACCEPTED;
    }
}
