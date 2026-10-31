package com.faststudio.v1.services.media;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.LibraryDto.*;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.repositories.media.MediaSettingsRepository;
import com.faststudio.v1.services.media.sources.MuxSource;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.mux.MuxWebhookVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.*;

/**
 * The video library connector (Mux). Platform administrators set each project's Mux account; projects sync their
 * library and get one-time direct-upload URLs without seeing the provider or its credentials; Mux webhooks
 * (signed with that project's secret) move uploads from Processing to Ready.
 */
@Service
public class LibraryServiceV1 {
    static final Set<String> EVENTS = Set.of("video.asset.created", "video.asset.ready", "video.asset.errored");
    static final String CONVERTING = "Received from the library; converting into " + Times.SEG_S + "-second segments";

    /** Outcome of a webhook delivery, mapped to an HTTP status by the controller. */
    public enum WebhookResult { UNKNOWN_PROJECT, BAD_SIGNATURE, ACCEPTED }

    private final Data data;
    private final MuxSource mux;
    private final MediaServiceV1 media;
    private final MediaSettingsServiceV1 settings;
    private final TenantConfigurationServiceV1 configuration;
    private final AssetRepository assets;
    private final MediaSettingsRepository projects;
    private final Audit audit;
    private final JsonMapper json;
    private final String appUrl;

    public LibraryServiceV1(Data data, MuxSource mux, MediaServiceV1 media, MediaSettingsServiceV1 settings,
                              TenantConfigurationServiceV1 configuration, AssetRepository assets, MediaSettingsRepository projects,
                              Audit audit, JsonMapper json, @Value("${faststudio.app-url}") String appUrl) {
        this.data = data;
        this.mux = mux;
        this.media = media;
        this.settings = settings;
        this.configuration = configuration;
        this.assets = assets;
        this.projects = projects;
        this.audit = audit;
        this.json = json;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ platform administration

    public AccountView account(UUID org) {
        requireCustomer(org);
        MuxSource.Account a = mux.account(org);
        String hook = appUrl + "/hooks/mux/" + org;
        return a == null ? new AccountView(false, null, null, null, false, hook)
                : new AccountView(true, null, a.environment(), a.tag(), a.webhookSecret() != null, hook);
    }

    public AccountView saveAccount(UUID org, AccountRequest r) {
        requireCustomer(org);
        String token = blankToNull(r.tokenId()), secret = blankToNull(r.tokenSecret());
        String env = Objects.toString(r.environment(), "").trim(), tag = Objects.toString(r.passthroughTag(), "").trim();
        if ((token == null || secret == null) && mux.account(org) == null)
            throw ApiException.bad("The access token ID and secret are required to connect a library.");
        mux.save(org, token, secret, blankToNull(r.webhookSecret()), env, tag);
        audit.platform(Auth.userId(), "platform.mux.update", "organization", org, "environment '" + env + "', passthrough tag '" + tag + "'");
        return account(org);
    }

    public SyncResult platformSync(UUID org) throws InterruptedException {
        requireCustomer(org);
        SyncResult r = importLibrary(org);
        audit.platform(Auth.userId(), "platform.mux.sync", "organization", org, r.videos() + " videos in Mux, " + r.added() + " added");
        return r;
    }

    private void requireCustomer(UUID org) {
        if (!projects.isCustomerOrganization(org)) throw ApiException.notFound("Organization");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // ------------------------------------------------------------------ project routes (provider-neutral "library")

    /** Projects sync themselves only with the LIBRARY_SYNC feature; otherwise the platform syncs from its Connectors page. */
    public SyncResult sync(Tenant t) throws InterruptedException {
        t.require(Tenant.Perm.ASSETS_EDIT);
        if (!configuration.effectiveFeatures(t.orgId(), assets.projectFeatures(t.orgId())).contains(Feature.LIBRARY_SYNC.name()))
            throw new ApiException(HttpStatus.FORBIDDEN, "FEATURE_OFF", "This isn't switched on for your company. Ask the platform administrator.");
        SyncResult r = importLibrary(t.orgId());
        audit.log(t, "library.sync", "asset", null, r.videos() + " videos in the library, " + r.added() + " added");
        return r;
    }

    /** A one-time URL the browser PUTs the video file to; the file goes straight to the library, never through us. */
    public Map<String, String> uploadUrl(Tenant t, UUID id) throws InterruptedException {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Data.Asset a = data.asset(t.orgId(), id);
        if (a.status().equals("PROCESSING")) throw ApiException.conflict("BUSY", "This video is already being processed.");
        MuxSource.Upload u = mux.upload(connected(t.orgId()), appUrl, a.title());
        assets.setUploadReference(t.orgId(), id, MuxSource.UPLOAD_REF + u.id());
        audit.log(t, "asset.upload", "asset", id, a.title() + " (direct upload)");
        return Map.of("url", u.url());
    }

    /** Imports every ready library video not imported yet (each is packaged into our segments once). */
    private SyncResult importLibrary(UUID org) throws InterruptedException {
        settings.require(org, "MOVIE");
        List<MuxSource.Asset> all = mux.assets(connected(org));
        return new SyncResult(all.size(), (int) all.stream().filter(x -> apply(org, x)).count());
    }

    /** The project's Mux account, if the connector is enabled for the project and an account is configured. */
    private MuxSource.Account connected(UUID org) {
        if (!configuration.driverEnabled(org, "MUX"))
            throw new ApiException(HttpStatus.FORBIDDEN, "CONNECTOR_DISABLED", "The video library connector is disabled for this project.");
        MuxSource.Account a = mux.account(org);
        if (a == null) throw ApiException.conflict("LIBRARY_NOT_CONNECTED", MuxSource.NOT_CONNECTED);
        return a;
    }

    /**
     * Brings one Mux asset into the project's library: a direct upload of ours is linked to the row made when its URL
     * was minted; any other ready asset becomes a new row. Packaging is queued once per row; true when it was.
     */
    private boolean apply(UUID org, MuxSource.Asset x) {
        return TenantScope.as(org, () -> {
            String ref = MuxSource.REF + x.id(), upload = x.uploadId() == null ? ref : MuxSource.UPLOAD_REF + x.uploadId();
            if ("errored".equals(x.status())) {
                assets.markLibraryFailed(org, ref, upload, "The library couldn't process this file. Check that it plays, then upload it again.");
                return false;
            }
            Optional<UUID> id = x.uploadId() == null ? Optional.empty() : assets.linkUploadedAsset(org, upload, ref, CONVERTING);
            if (id.isEmpty() && "ready".equals(x.status()))
                id = assets.insertLibraryAsset(org, x.title(), x.durationMs(), CONVERTING, AssetServiceV1.defaultCues("MOVIE", x.durationMs()), ref);
            id.ifPresent(media::enqueue);
            return id.isPresent();
        });
    }

    // ------------------------------------------------------------------ webhooks (configured per project in Mux)

    public WebhookResult webhook(UUID org, String signature, byte[] body) throws GeneralSecurityException {
        MuxSource.Account a = mux.account(org);
        if (a == null || a.webhookSecret() == null) return WebhookResult.UNKNOWN_PROJECT;
        if (!MuxWebhookVerifier.signed(a.webhookSecret(), signature, body, Instant.now())) return WebhookResult.BAD_SIGNATURE;
        JsonNode event = json.readTree(body);
        MuxSource.Asset x = EVENTS.contains(event.path("type").asString("")) ? MuxSource.Asset.of(event.path("data")) : null;
        if (x != null && a.includes(x)) apply(org, x);
        return WebhookResult.ACCEPTED;
    }
}
