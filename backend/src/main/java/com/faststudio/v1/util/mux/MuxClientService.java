package com.faststudio.v1.util.mux;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * HTTP client for the Mux video API: catalogue paging, asset detail, direct uploads and master-file access. Knows nothing
 * about where credentials are stored (see MuxConnectionService) or how files are packaged (see MuxSource).
 * White-label (HANDOVER §4.5): its errors never name the provider, because they can reach tenants.
 */
@Component
public class MuxClientService {
    private static final Logger log = LoggerFactory.getLogger(MuxClientService.class);
    static final int PAGE = 100;
    static final Duration MASTER_WAIT = Duration.ofMinutes(30);

    private final JsonMapper json;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public MuxClientService(JsonMapper json, @Value("${faststudio.mux.api-url:https://api.mux.com}") String apiUrl) {
        this.json = json;
        this.apiUrl = apiUrl.replaceAll("/+$", "");
    }

    /** A tenant's account, decrypted. tag = the passthrough value marking the tenant's FAST videos (blank = all). */
    public record Account(String tokenId, String tokenSecret, String webhookSecret, String environment, String tag) {
        public boolean includes(Asset a) { return tag.isEmpty() || tag.equals(a.passthrough()); }
    }

    /** The fields we use from a Mux asset object (listings, asset detail and webhook payloads share it). title: trimmed, may be blank. */
    public record Asset(String id, String status, long durationMs, String passthrough, String uploadId, String title,
                        String masterAccess, String masterStatus, String masterUrl) {
        /** Null for anything that isn't a well-formed asset (ids go into API paths and source_ref). */
        public static Asset of(JsonNode n) {
            String id = n.path("id").asString("");
            if (!id.matches("[A-Za-z0-9]{1,128}")) return null;
            String upload = n.path("upload_id").asString("");
            long ms = (long) (n.path("duration").asDouble(0) * 1000) / Times.SEG_MS * Times.SEG_MS;   // whole segments, as packaging keeps
            return new Asset(id, n.path("status").asString(""), ms, n.path("passthrough").asString(""), upload.isEmpty() ? null : upload,
                    n.path("meta").path("title").asString("").trim(),
                    n.path("master_access").asString("none"), n.path("master").path("status").asString(""), n.path("master").path("url").asString(""));
        }
    }

    public record Upload(String id, String url) {}

    /** Every asset of the account carrying its tag, page by page (cursor when Mux returns one, else page numbers). */
    public List<Asset> assets(Account a) throws InterruptedException {
        List<Asset> out = new ArrayList<>();
        String cursor = null;
        for (int page = 1; page <= 1000; page++) {
            JsonNode res = call(a, "GET", "/video/v1/assets?limit=" + PAGE
                    + (cursor == null ? "&page=" + page : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)), null);
            JsonNode data = res.path("data");
            for (JsonNode n : data) {
                Asset x = Asset.of(n);
                if (x != null && a.includes(x)) out.add(x);
            }
            cursor = res.path("next_cursor").asString("");
            if (cursor.isEmpty()) cursor = null;
            if (cursor == null && data.size() < PAGE) break;
        }
        return out;
    }

    public Asset asset(Account a, String id) throws InterruptedException {
        return Asset.of(call(a, "GET", "/video/v1/assets/" + id, null).path("data"));
    }

    /** One-time URL the browser PUTs the file to. The asset it becomes carries the tenant's tag and keeps its master file. */
    public Upload upload(Account a, String corsOrigin, String title) throws InterruptedException {
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("master_access", "temporary", "meta", Map.of("title", title)));
        if (!a.tag().isEmpty()) settings.put("passthrough", a.tag());
        JsonNode d = call(a, "POST", "/video/v1/uploads", json.writeValueAsString(Map.of("cors_origin", corsOrigin, "new_asset_settings", settings))).path("data");
        String id = d.path("id").asString(""), url = d.path("url").asString("");
        if (!id.matches("[A-Za-z0-9]{1,128}") || !url.startsWith("https://")) throw unavailable(0);
        return new Upload(id, url);
    }

    /**
     * Temporary download URL of the asset's original file: requests master access when needed, then waits while Mux
     * prepares it (and, for a fresh upload, while Mux is still processing the asset). Null if it errored or timed out.
     * ponytail: polls on a packaging thread; drive it from the video.asset.master.ready webhook if many arrive at once.
     */
    public String masterUrl(Account a, String id) throws InterruptedException {
        long deadline = System.nanoTime() + MASTER_WAIT.toNanos();
        while (true) {
            Asset x = asset(a, id);
            if (x == null || "errored".equals(x.status())) return null;
            if ("ready".equals(x.masterStatus()) && x.masterUrl().startsWith("https://")) return x.masterUrl();
            if ("ready".equals(x.status()) && !"temporary".equals(x.masterAccess()))
                call(a, "PUT", "/video/v1/assets/" + id + "/master-access", "{\"master_access\":\"temporary\"}");
            if (System.nanoTime() > deadline) return null;
            Thread.sleep(10_000);
        }
    }

    /** One API call. Failures become neutral, tenant-safe errors; the details stay in the server log. */
    JsonNode call(Account a, String method, String path, String body) throws InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((a.tokenId() + ":" + a.tokenSecret()).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Mux {} {} failed ({})", method, path, e.getClass().getSimpleName());
            throw unavailable(0);
        }
        if (res.statusCode() / 100 != 2) {
            log.warn("Mux {} {} answered {}", method, path, res.statusCode());
            throw unavailable(res.statusCode());
        }
        return json.readTree(res.body());
    }

    static ApiException unavailable(int status) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "LIBRARY_UNAVAILABLE", status == 401 || status == 403
                ? "The video library refused the connection. Ask your platform administrator to check it."
                : "The video library couldn't be reached" + (status > 0 ? " (HTTP " + status + ")" : "") + ". Try again later.");
    }
}
