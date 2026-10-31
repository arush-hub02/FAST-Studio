package com.faststudio.media;

import com.faststudio.core.ApiException;
import com.faststudio.core.Secrets;
import com.faststudio.core.TenantScope;
import com.faststudio.core.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Content connector for a tenant's account on the Mux video platform. Mux holds the tenant's catalogue; we pull each
 * asset's original (master) file once and package it into our own 5 s segments on R2, so viewers are never served,
 * or billed per minute, by Mux. Accounts are per tenant, set by a platform administrator and encrypted at rest.
 * White-label (HANDOVER §4.5): tenants see "the library"; no message that can reach them names the provider.
 */
@Component
public class MuxSource implements ContentSource {
    private static final Logger log = LoggerFactory.getLogger(MuxSource.class);
    public static final String REF = "mux:", UPLOAD_REF = "mux-upload:";
    static final String NOT_CONNECTED = "The video library isn't connected for this organization yet. Ask your platform administrator.";
    static final int PAGE = 100;
    static final Duration MASTER_WAIT = Duration.ofMinutes(30);

    private final JdbcClient db;
    private final Secrets secrets;
    private final JsonMapper json;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public MuxSource(JdbcClient db, Secrets secrets, JsonMapper json, @Value("${faststudio.mux.api-url:https://api.mux.com}") String apiUrl) {
        this.db = db;
        this.secrets = secrets;
        this.json = json;
        this.apiUrl = apiUrl.replaceAll("/+$", "");
    }

    /** A tenant's account, decrypted. tag = the passthrough value marking the tenant's FAST videos (blank = all). */
    public record Account(String tokenId, String tokenSecret, String webhookSecret, String environment, String tag) {
        public boolean includes(Asset a) { return tag.isEmpty() || tag.equals(a.passthrough()); }
    }

    record Row(String tokenId, String tokenSecret, String webhookSecret, String environment, String passthroughTag) {}

    /** The tenant's account, or null when none is set up. */
    public Account account(UUID org) {
        return TenantScope.as(org, () -> db.sql("select token_id, token_secret, webhook_secret, environment, passthrough_tag from mux_account where org_id = :o")
                .param("o", org).query(Row.class).optional()
                .map(r -> new Account(secrets.open(r.tokenId()), secrets.open(r.tokenSecret()), secrets.open(r.webhookSecret()), r.environment(), r.passthroughTag()))
                .orElse(null));
    }

    /** Stores the account encrypted; a null token id or secret keeps the stored one. */
    public void save(UUID org, String tokenId, String tokenSecret, String webhookSecret, String environment, String tag) {
        Map<String, Object> p = new HashMap<>(Map.of("o", org, "e", environment, "t", tag));
        p.put("i", secrets.seal(tokenId));
        p.put("s", secrets.seal(tokenSecret));
        p.put("w", secrets.seal(webhookSecret));
        if (db.sql("""
                update mux_account set token_id = coalesce(:i, token_id), token_secret = coalesce(:s, token_secret), webhook_secret = coalesce(:w, webhook_secret),
                  environment = :e, passthrough_tag = :t, updated_at = now() where org_id = :o""").params(p).update() == 0)
            db.sql("insert into mux_account(org_id, token_id, token_secret, webhook_secret, environment, passthrough_tag) values (:o, :i, :s, :w, :e, :t)")
                    .params(p).update();
    }

    /** The fields we use from a Mux asset object (listings, asset detail and webhook payloads share it). */
    public record Asset(String id, String status, long durationMs, String passthrough, String uploadId, String title,
                        String masterAccess, String masterStatus, String masterUrl) {
        /** Null for anything that isn't a well-formed asset (ids go into API paths and source_ref). */
        public static Asset of(JsonNode n) {
            String id = n.path("id").asString("");
            if (!id.matches("[A-Za-z0-9]{1,128}")) return null;
            String upload = n.path("upload_id").asString("");
            String title = n.path("meta").path("title").asString("").trim();
            long ms = (long) (n.path("duration").asDouble(0) * 1000) / Times.SEG_MS * Times.SEG_MS;   // whole segments, as packaging keeps
            return new Asset(id, n.path("status").asString(""), ms, n.path("passthrough").asString(""), upload.isEmpty() ? null : upload,
                    title.isEmpty() ? "Untitled video" : title.length() > 120 ? title.substring(0, 120) : title,
                    n.path("master_access").asString("none"), n.path("master").path("status").asString(""), n.path("master").path("url").asString(""));
        }
    }

    public record Upload(String id, String url) {}

    // ------------------------------------------------------------------ Mux API

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
            log.warn("Mux {} {} failed: {}", method, path, e.toString());
            throw unavailable(0);
        }
        if (res.statusCode() / 100 != 2) {
            log.warn("Mux {} {} answered {}: {}", method, path, res.statusCode(), res.body());
            throw unavailable(res.statusCode());
        }
        return json.readTree(res.body());
    }

    static ApiException unavailable(int status) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "LIBRARY_UNAVAILABLE", status == 401 || status == 403
                ? "The video library refused the connection. Ask your platform administrator to check it."
                : "The video library couldn't be reached" + (status > 0 ? " (HTTP " + status + ")" : "") + ". Try again later.");
    }

    // ------------------------------------------------------------------ ContentSource

    @Override
    public boolean handles(MediaService.Job job) { return job.sourceRef() != null && job.sourceRef().startsWith(REF); }

    @Override
    public String ingest(MediaService.Job j, Path out, MediaService packager) throws Exception {
        Account a = account(j.orgId());
        if (a == null) return NOT_CONNECTED;
        String url;
        try {
            url = masterUrl(a, j.sourceRef().substring(REF.length()));
        } catch (ApiException e) {
            return e.getMessage();
        }
        if (url == null) return "The library couldn't provide this video's original file. Try again later, or upload the file again.";
        String err = RemoteSource.encodeUrl(url, out, packager);
        if (err == null) return null;
        log.warn("Packaging {} from Mux failed: {}", j.id(), err);
        return "The library video couldn't be converted into " + Times.SEG_S + "-second segments. Try again later.";
    }

    /**
     * Temporary download URL of the asset's original file: requests master access when needed, then waits while Mux
     * prepares it (and, for a fresh upload, while Mux is still processing the asset). Null if it errored or timed out.
     * ponytail: polls on a packaging thread; drive it from the video.asset.master.ready webhook if many arrive at once.
     */
    String masterUrl(Account a, String id) throws InterruptedException {
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
}
