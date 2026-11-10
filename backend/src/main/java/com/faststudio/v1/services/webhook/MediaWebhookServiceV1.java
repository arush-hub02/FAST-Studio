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
import java.util.Map;
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
        // a project may have several Mux connections on one webhook URL: the secret that signed it says which one
        var withSecret = connections.accounts(org).stream().filter(e -> e.getValue().webhookSecret() != null).toList();
        if (withSecret.isEmpty()) return Result.UNKNOWN_PROJECT;
        Map.Entry<UUID, MuxClientService.Account> signedBy = null;
        Instant now = Instant.now();
        for (var e : withSecret) if (MuxWebhookVerifier.signed(e.getValue().webhookSecret(), signature, body, now)) { signedBy = e; break; }
        if (signedBy == null) return Result.BAD_SIGNATURE;
        MuxClientService.Account a = signedBy.getValue();
        MediaConnectionService.Connection c = connections.connection(org, signedBy.getKey());
        if (c == null) return Result.UNKNOWN_PROJECT;
        JsonNode event = json.readTree(body);
        String type = event.path("type").asString("");
        MuxClientService.Asset x = MUX_EVENTS.contains(type) ? MuxClientService.Asset.of(event.path("data")) : null;
        if (x != null && a.includes(x)) {
            RemoteAsset r = MuxProvider.normalize(x);
            library.apply(org, c, type.equals("video.asset.deleted") ? r.withStatus("DELETED") : r);
        }
        return Result.ACCEPTED;
    }
}
