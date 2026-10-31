package com.faststudio.v1_0.controllers.platform.connectors;

import com.faststudio.v1_0.controllers.studio.media.AssetControllerV1_0;
import com.faststudio.v1_0.core.auth.Auth;
import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.common.Feature;
import com.faststudio.v1_0.core.common.Times;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.core.tenant.TenantScope;
import com.faststudio.v1_0.services.media.MediaServiceV1_0;
import com.faststudio.v1_0.services.media.MediaSettingsServiceV1_0;
import com.faststudio.v1_0.services.media.sources.MuxSource;
import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;


import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * The video library connector over HTTP. Platform administrators set each tenant's Mux account; tenants sync their
 * library and get one-time direct-upload URLs without ever seeing the provider or its credentials; Mux webhooks
 * (signed with that tenant's secret) move uploads from Processing to Ready.
 */
@RestController
public class MuxControllerV1_0 {
    static final Set<String> EVENTS = Set.of("video.asset.created", "video.asset.ready", "video.asset.errored");
    static final String CONVERTING = "Received from the library; converting into " + Times.SEG_S + "-second segments";

    private final Data data;
    private final MuxSource mux;
    private final MediaServiceV1_0 media;
    private final Audit audit;
    private final JsonMapper json;
    private final String appUrl;
    private final MediaSettingsServiceV1_0 settings;
    private final TenantConfigurationServiceV1_0 configuration;

    public MuxControllerV1_0(Data data, MuxSource mux, MediaServiceV1_0 media, Audit audit, JsonMapper json, @Value("${faststudio.app-url}") String appUrl, MediaSettingsServiceV1_0 settings,
                         TenantConfigurationServiceV1_0 configuration) {
        this.data = data;
        this.mux = mux;
        this.media = media;
        this.audit = audit;
        this.json = json;
        this.appUrl = appUrl.replaceAll("/+$", "");
        this.settings = settings;
        this.configuration=configuration;
    }

    // ------------------------------------------------------------------ platform administration (WebConfig: admins only)

    /** Blank token id / secrets keep the stored ones. passthroughTag: only Mux assets with exactly this passthrough are imported (blank = all). */
    public record AccountRequest(@Size(max = 200) String tokenId, @Size(max = 500) String tokenSecret, @Size(max = 500) String webhookSecret,
                                 @Size(max = 120) String environment, @Size(max = 255) String passthroughTag) {}

    /** Never carries a secret. webhookUrl is what to enter in the Mux environment's webhook settings. */
    public record AccountView(boolean connected, String tokenId, String environment, String passthroughTag, boolean webhookSecretSet, String webhookUrl) {}

    @GetMapping("/api/platform/orgs/{orgId}/connectors/mux")
    AccountView account(@PathVariable("orgId") UUID id) {
        customer(id);
        MuxSource.Account a = mux.account(id);
        String hook = appUrl + "/hooks/mux/" + id;
        return a == null ? new AccountView(false, null, null, null, false, hook)
                : new AccountView(true, null, a.environment(), a.tag(), a.webhookSecret() != null, hook);
    }

    @PutMapping("/api/platform/orgs/{orgId}/connectors/mux")
    AccountView setAccount(@PathVariable("orgId") UUID id, @Valid @RequestBody AccountRequest r) {
        customer(id);
        String token = blankToNull(r.tokenId()), secret = blankToNull(r.tokenSecret()), env = Objects.toString(r.environment(), "").trim(),
                tag = Objects.toString(r.passthroughTag(), "").trim();
        if ((token == null || secret == null) && mux.account(id) == null) throw ApiException.bad("The access token ID and secret are required to connect a library.");
        mux.save(id, token, secret, blankToNull(r.webhookSecret()), env, tag);
        audit.platform(Auth.userId(), "platform.mux.update", "organization", id, "environment '" + env + "', passthrough tag '" + tag + "'");
        return account(id);
    }

    @PostMapping("/api/platform/orgs/{orgId}/connectors/mux/sync")
    SyncResult platformSync(@PathVariable("orgId") UUID id) throws InterruptedException {
        customer(id);
        SyncResult r = importLibrary(id);
        audit.platform(Auth.userId(), "platform.mux.sync", "organization", id, r.videos() + " videos in Mux, " + r.added() + " added");
        return r;
    }

    private void customer(UUID id) {
        if (data.db.sql("select count(*) from organization where id = :id and kind = 'CUSTOMER'").param("id", id).query(Integer.class).single() == 0)
            throw ApiException.notFound("Organization");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // ------------------------------------------------------------------ tenant routes: provider-neutral ("library")

    public record SyncResult(int videos, int added) {}

    /** Imports every ready library video not imported yet (each is packaged into our segments once). */
    SyncResult importLibrary(UUID org) throws InterruptedException {
        settings.require(org,"MOVIE");
        List<MuxSource.Asset> all = mux.assets(connected(org));
        return new SyncResult(all.size(), (int) all.stream().filter(x -> apply(org, x)).count());
    }

    /** Companies sync themselves only with the LIBRARY_SYNC feature; otherwise the platform does it from the Connectors page. */
    @PostMapping("/api/orgs/{org}/library/sync")
    SyncResult sync(Tenant t) throws InterruptedException {
        t.require(Tenant.Perm.ASSETS_EDIT);
        String features = data.db.sql("select features from organization where id = :o").param("o", t.orgId()).query(String.class).single();
        if (!configuration.effectiveFeatures(t.orgId(),features).contains(Feature.LIBRARY_SYNC.name()))
            throw new ApiException(HttpStatus.FORBIDDEN, "FEATURE_OFF", "This isn't switched on for your company. Ask the platform administrator.");
        SyncResult r = importLibrary(t.orgId());
        audit.log(t, "library.sync", "asset", null, r.videos() + " videos in the library, " + r.added() + " added");
        return r;
    }

    /** A one-time URL the browser PUTs the video file to; the file goes straight to the library, never through us. */
    @PostMapping("/api/orgs/{org}/assets/{id}/upload-url")
    Map<String, String> uploadUrl(Tenant t, @PathVariable UUID id) throws InterruptedException {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Data.Asset a = data.asset(t.orgId(), id);
        if (a.status().equals("PROCESSING")) throw ApiException.conflict("BUSY", "This video is already being processed.");
        MuxSource.Upload u = mux.upload(connected(t.orgId()), appUrl, a.title());
        data.db.sql("update asset set source_ref = :r, status_detail = 'Waiting for the upload to finish', updated_at = now() where org_id = :o and id = :id")
                .param("r", MuxSource.UPLOAD_REF + u.id()).param("o", t.orgId()).param("id", id).update();
        audit.log(t, "asset.upload", "asset", id, a.title() + " (direct upload)");
        return Map.of("url", u.url());
    }

    private MuxSource.Account connected(UUID org) {
        if (!configuration.driverEnabled(org,"MUX")) throw new ApiException(HttpStatus.FORBIDDEN,"CONNECTOR_DISABLED","The video library connector is disabled for this project.");
        MuxSource.Account a = mux.account(org);
        if (a == null) throw ApiException.conflict("LIBRARY_NOT_CONNECTED", MuxSource.NOT_CONNECTED);
        return a;
    }

    /**
     * Brings one Mux asset into the tenant's library: a direct upload of ours is linked to the row made when its URL
     * was minted; any other ready asset becomes a new row. Packaging is queued once per row; true when it was.
     */
    boolean apply(UUID org, MuxSource.Asset x) {
        return TenantScope.as(org, () -> {
            String ref = MuxSource.REF + x.id(), upload = x.uploadId() == null ? ref : MuxSource.UPLOAD_REF + x.uploadId();
            if ("errored".equals(x.status())) {
                data.db.sql("update asset set status = 'FAILED', status_detail = :d, updated_at = now() where org_id = :o and source_ref in (:r, :u) and status not in ('READY', 'ARCHIVED')")
                        .param("d", "The library couldn't process this file. Check that it plays, then upload it again.")
                        .param("o", org).param("r", ref).param("u", upload).update();
                return false;
            }
            Optional<UUID> id = x.uploadId() == null ? Optional.empty() : data.db.sql("""
                    update asset set source_ref = :r, status = 'PROCESSING', status_detail = :d, updated_at = now()
                    where org_id = :o and source_ref = :u returning id""")
                    .param("r", ref).param("d", CONVERTING).param("o", org).param("u", upload).query(UUID.class).optional();
            if (id.isEmpty() && "ready".equals(x.status()))
                id = data.db.sql("""
                        insert into asset(org_id, title, kind, duration_ms, status, status_detail, cue_points, source_ref)
                        values (:o, :t, 'MOVIE', :ms, 'PROCESSING', :d, :c, :r)
                        on conflict (org_id, source_ref) where source_ref is not null do nothing returning id""")
                        .param("o", org).param("t", x.title()).param("ms", x.durationMs()).param("d", CONVERTING)
                        .param("c", AssetControllerV1_0.defaultCues("MOVIE", x.durationMs())).param("r", ref).query(UUID.class).optional();
            id.ifPresent(media::enqueue);
            return id.isPresent();
        });
    }

    // ------------------------------------------------------------------ webhooks (configured per tenant in Mux)

    @PostMapping("/hooks/mux/{org}")
    ResponseEntity<Void> webhook(@PathVariable UUID org, @RequestHeader(value = "Mux-Signature", required = false) String signature,
                                 @RequestBody byte[] body) throws GeneralSecurityException {
        MuxSource.Account a = mux.account(org);
        if (a == null || a.webhookSecret() == null) return ResponseEntity.notFound().build();
        if (!signed(a.webhookSecret(), signature, body, Instant.now())) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        JsonNode event = json.readTree(body);
        MuxSource.Asset x = EVENTS.contains(event.path("type").asString("")) ? MuxSource.Asset.of(event.path("data")) : null;
        if (x != null && a.includes(x)) apply(org, x);
        return ResponseEntity.noContent().build();
    }

    /** Mux-Signature: "t={unix seconds},v1={hex HMAC-SHA256 of t + '.' + raw body}", accepted within 5 minutes. */
    static boolean signed(String secret, String header, byte[] body, Instant now) throws GeneralSecurityException {
        if (header == null) return false;
        String t = null, v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) t = kv[1];
            if (kv.length == 2 && kv[0].equals("v1")) v1 = kv[1];
        }
        if (t == null || v1 == null || !t.matches("\\d{1,12}") || !v1.matches("[0-9a-fA-F]{64}") || Math.abs(now.getEpochSecond() - Long.parseLong(t)) > 300)
            return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(v1));
    }
}
