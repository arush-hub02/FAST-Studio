package com.faststudio.v1.services.media;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.LibraryDto.SyncResult;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.services.media.sources.MuxSource;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.services.tenancy.TenantLimitsService;
import com.faststudio.v1.util.mux.MuxClientService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The video library (Mux) use cases a project sees under provider-neutral names: catalogue sync and one-time
 * direct-upload URLs. The account itself is {@link MuxConnectionService}; signed webhooks arrive through
 * MuxWebhookServiceV1 and end in {@link #apply}, the same path a sync takes.
 */
@Service
public class LibraryServiceV1 {
    static final String CONVERTING = "Received from the library; converting into " + Times.SEG_S + "-second segments";

    private final Data data;
    private final MuxClientService mux;
    private final MuxConnectionService connections;
    private final MediaServiceV1 media;
    private final AssetRules rules;
    private final TenantConfigurationServiceV1 configuration;
    private final TenantLimitsService limits;
    private final AssetRepository assets;
    private final Audit audit;
    private final String appUrl;

    public LibraryServiceV1(Data data, MuxClientService mux, MuxConnectionService connections, MediaServiceV1 media, AssetRules rules,
                            TenantConfigurationServiceV1 configuration, TenantLimitsService limits, AssetRepository assets, Audit audit,
                            @Value("${faststudio.app-url}") String appUrl) {
        this.data = data;
        this.mux = mux;
        this.connections = connections;
        this.media = media;
        this.rules = rules;
        this.configuration = configuration;
        this.limits = limits;
        this.assets = assets;
        this.audit = audit;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    /** Platform administrators sync any customer project from the Connectors page. */
    public SyncResult platformSync(UUID org) throws InterruptedException {
        connections.requireCustomer(org);
        SyncResult r = importLibrary(org);
        audit.platform(Auth.userId(), "platform.mux.sync", "organization", org, r.videos() + " videos in Mux, " + r.added() + " added");
        return r;
    }

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
        limits.requireMediaRoom(t.orgId());
        limits.requireProcessingSlot(t.orgId());
        MuxClientService.Upload u = mux.upload(connections.connected(t.orgId()), appUrl, a.title());
        assets.setUploadReference(t.orgId(), id, MuxSource.UPLOAD_REF + u.id());
        audit.log(t, "asset.upload", "asset", id, a.title() + " (direct upload)");
        return Map.of("url", u.url());
    }

    /** Imports every ready library video not imported yet (each is packaged into our segments once). */
    private SyncResult importLibrary(UUID org) throws InterruptedException {
        // ponytail: checked once before the sync, so one sync can go past the media limit; check per video if that matters
        limits.requireMediaRoom(org);
        rules.kindOf(org, "MOVIE");   // library videos arrive as movies; fails clearly if the project has that type switched off
        List<MuxClientService.Asset> all = mux.assets(connections.connected(org));
        return new SyncResult(all.size(), (int) all.stream().filter(x -> apply(org, x)).count());
    }

    /**
     * Brings one Mux asset into the project's library: a direct upload of ours is linked to the row made when its URL
     * was minted; any other ready asset becomes a new row. Packaging is queued once per row; true when it was.
     * Idempotent, so webhook redelivery and re-sync never duplicate rows.
     */
    public boolean apply(UUID org, MuxClientService.Asset x) {
        return TenantScope.as(org, () -> {
            String ref = MuxSource.REF + x.id(), upload = x.uploadId() == null ? ref : MuxSource.UPLOAD_REF + x.uploadId();
            if ("errored".equals(x.status())) {
                assets.markLibraryFailed(org, ref, upload, "The library couldn't process this file. Check that it plays, then upload it again.");
                return false;
            }
            Optional<UUID> id = x.uploadId() == null ? Optional.empty() : assets.linkUploadedAsset(org, upload, ref, CONVERTING);
            if (id.isEmpty() && "ready".equals(x.status()))
                id = assets.insertLibraryAsset(org, AssetRules.importedTitle(x.title()), x.durationMs(), CONVERTING,
                        AssetRules.defaultCues("MOVIE", x.durationMs()), ref);
            id.ifPresent(media::enqueue);
            return id.isPresent();
        });
    }
}
