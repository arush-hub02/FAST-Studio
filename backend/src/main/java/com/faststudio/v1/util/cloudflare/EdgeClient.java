package com.faststudio.v1.util.cloudflare;

import com.faststudio.v1.services.media.MediaStore;

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

/**
 * Talks to the Cloudflare Worker in worker/: uploads packaged media to R2 (the {@link MediaStore} connector),
 * publishes channel schedule snapshots to KV, and signs the viewer session tokens the Worker trusts.
 * One shared secret does both: it is the bearer token for uploads and the HMAC key for session tokens.
 */
@Component
public class EdgeClient implements MediaStore {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String url, publicUrl;
    private final byte[] secret;

    public EdgeClient(@Value("${faststudio.edge.url}") String url, @Value("${faststudio.edge.public-url}") String publicUrl,
                      @Value("${faststudio.edge.secret:}") String secret) {
        this.url = url.replaceAll("/+$", "");
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean configured() { return secret.length >= 32; }

    public String publicUrl() { return publicUrl; }

    @Override
    public boolean has(String mediaKey) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(url + "/media/" + mediaKey + "/master.m3u8"))
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
        var req = HttpRequest.newBuilder(URI.create(url + path)).PUT(body).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + new String(secret, StandardCharsets.UTF_8)).header("Content-Type", contentType).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException("Edge refused " + path + ": HTTP " + res.statusCode() + " " + res.body());
    }

    /** Live stream URL for one viewer session: /live/{base64url(json)}.{base64url(hmac)}/master.m3u8. */
    public String liveUrl(String sessionJson) {
        return publicUrl + "/live/" + token(sessionJson) + "/master.m3u8";
    }

    /** First rendition's live playlist for a session, fetched like a player would (ops.AvailVerifier). */
    public String livePlaylist(String sessionJson) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(url + "/live/" + token(sessionJson) + "/v0.m3u8")).timeout(Duration.ofSeconds(10)).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Edge answered HTTP " + res.statusCode() + ": " + res.body());
        return res.body();
    }

    private String token(String sessionJson) {
        String payload = B64.encodeToString(sessionJson.getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(hmac(payload));
    }

    byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
