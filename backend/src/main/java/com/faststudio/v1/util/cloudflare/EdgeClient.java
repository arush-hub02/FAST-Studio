package com.faststudio.v1.util.cloudflare;

import com.faststudio.v1.services.connectors.ConnectorCatalogueServiceV1;
import com.faststudio.v1.services.media.MediaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Talks to the Cloudflare Worker in worker/: uploads packaged media to R2 (the {@link MediaStore} connector),
 * publishes channel schedule snapshots to KV, and signs the viewer session tokens the Worker trusts.
 * One shared secret does both: it is the bearer token for uploads and the HMAC key for session tokens.
 * Settings come from the platform's R2 connection (connector fields worker_url, edge_secret, public_url) and fall back to
 * faststudio.edge.* for fields not stored there. The Worker must hold the same secret; the page security policy still
 * uses faststudio.edge.public-url, read at start-up.
 */
@Component
public class EdgeClient implements MediaStore {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Logger log = LoggerFactory.getLogger(EdgeClient.class);
    private static final long CACHE_MS = 30_000;
    private final Settings fallback;
    private final ObjectProvider<ConnectorCatalogueServiceV1> connectors;   // lazy: the catalogue isn't needed to build this bean
    private volatile Settings cached;
    private volatile long cachedAt;

    private record Settings(String url, String publicUrl, byte[] secret) {}

    public EdgeClient(@Value("${faststudio.edge.url}") String url, @Value("${faststudio.edge.public-url}") String publicUrl,
                      @Value("${faststudio.edge.secret:}") String secret, ObjectProvider<ConnectorCatalogueServiceV1> connectors) {
        this.fallback = new Settings(trim(url), trim(publicUrl), secret.getBytes(StandardCharsets.UTF_8));
        this.connectors = connectors;
    }

    /** The platform's R2 connection over the start-up settings, field by field (read at most every 30 s). */
    private Settings settings() {
        long now = System.currentTimeMillis();
        Settings s = cached;
        if (s != null && now - cachedAt < CACHE_MS) return s;
        Map<String, String> v = Map.of();
        try {
            ConnectorCatalogueServiceV1 c = connectors.getIfAvailable();
            if (c != null) v = c.connection(null, "r2").map(ConnectorCatalogueServiceV1.Connection::values).orElse(Map.of());
        } catch (RuntimeException e) {
            log.warn("Couldn't read the R2 connection; using the start-up edge settings", e);
        }
        s = new Settings(v.containsKey("worker_url") ? trim(v.get("worker_url")) : fallback.url(),
                v.containsKey("public_url") ? trim(v.get("public_url")) : fallback.publicUrl(),
                v.containsKey("edge_secret") ? v.get("edge_secret").getBytes(StandardCharsets.UTF_8) : fallback.secret());
        cached = s;
        cachedAt = now;
        return s;
    }

    /** Forgets the cached settings, so a changed R2 connection applies at once. */
    public void refresh() { cached = null; }

    private static String trim(String u) { return u.replaceAll("/+$", ""); }

    public boolean configured() { return settings().secret().length >= 32; }

    public String publicUrl() { return settings().publicUrl(); }

    @Override
    public boolean has(String mediaKey) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(settings().url() + "/media/" + mediaKey + "/master.m3u8"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    @Override
    public void put(String path, Path file, String contentType) throws IOException, InterruptedException {
        send("/_ingest/media/" + path, contentType, HttpRequest.BodyPublishers.ofFile(file));
    }

    /** Replaces the schedule snapshot the Worker builds a channel's live playlists from. */
    public void putChannel(String org, String channel, String json) throws IOException, InterruptedException {
        send("/_ingest/channels/" + org + "/" + channel, "application/json", HttpRequest.BodyPublishers.ofString(json));
    }

    private void send(String path, String contentType, HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(settings().url() + path)).PUT(body).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + new String(settings().secret(), StandardCharsets.UTF_8)).header("Content-Type", contentType).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException("Edge refused " + path + ": HTTP " + res.statusCode() + " " + res.body());
    }

    /** Live stream URL for one viewer session: /live/{base64url(json)}.{base64url(hmac)}/master.m3u8. */
    public String liveUrl(String sessionJson) {
        return settings().publicUrl() + "/live/" + token(sessionJson) + "/master.m3u8";
    }

    /** First rendition's live playlist for a session, fetched like a player would (ops.AvailVerifier). */
    public String livePlaylist(String sessionJson) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(settings().url() + "/live/" + token(sessionJson) + "/v0.m3u8")).timeout(Duration.ofSeconds(10)).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Edge answered HTTP " + res.statusCode() + ": " + res.body());
        return res.body();
    }

    private String token(String sessionJson) {
        // without the shared secret no viewer link can be signed: say so instead of failing with a server error
        if (!configured()) throw new com.faststudio.v1.core.common.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "EDGE_NOT_CONFIGURED", "Live playback isn't set up yet: the edge secret is missing. Ask the platform administrator.");
        String payload = B64.encodeToString(sessionJson.getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(hmac(payload));
    }

    byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings().secret(), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
