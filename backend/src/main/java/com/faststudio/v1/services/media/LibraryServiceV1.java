package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.LibraryDto.PrepareResult;
import com.faststudio.v1.dto.media.LibraryDto.Skipped;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.repositories.media.AssetSourceRepository;
import com.faststudio.v1.repositories.media.AssetSourceRepository.Candidate;
import com.faststudio.v1.services.tenancy.TenantLimitsService;
import com.faststudio.v1.util.mediaProvider.Capability;
import com.faststudio.v1.util.mediaProvider.IDirectUpload;
import com.faststudio.v1.util.mediaProvider.IMediaProvider.RemoteAsset;
import com.faststudio.v1.util.mediaProvider.MediaProviderResolverService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The connected video library as a project sees it, under provider-neutral names: one-time direct-upload URLs, the
 * idempotent import of one library video ({@link #apply}, shared by sync and webhooks) and "prepare selected".
 * Library videos arrive catalogue-only (Draft); preparing them for broadcast costs provider and encoding work, so it
 * happens only for videos someone picks. A direct upload is the exception: it was uploaded to air, so it prepares at once.
 */
@Service
public class LibraryServiceV1 {
    static final String CONVERTING = "Received from the library; converting into " + Times.SEG_S + "-second segments";
    static final String CATALOGUED = "In the library; not prepared for broadcast yet";
    static final String PREPARING = "Preparing for broadcast: converting into " + Times.SEG_S + "-second segments";

    private final Data data;
    private final MediaConnectionService connections;
    private final MediaProviderResolverService providers;
    private final MediaServiceV1 media;
    private final TenantLimitsService limits;
    private final AssetRepository assets;
    private final AssetSourceRepository sources;
    private final Audit audit;
    private final String appUrl;

    public LibraryServiceV1(Data data, MediaConnectionService connections, MediaProviderResolverService providers, MediaServiceV1 media,
                            TenantLimitsService limits, AssetRepository assets, AssetSourceRepository sources, Audit audit,
                            @Value("${faststudio.app-url}") String appUrl) {
        this.data = data;
        this.connections = connections;
        this.providers = providers;
        this.media = media;
        this.limits = limits;
        this.assets = assets;
        this.sources = sources;
        this.audit = audit;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    /** A one-time URL the browser PUTs the video file to; the file goes straight to the library, never through us. */
    public Map<String, String> uploadUrl(Tenant t, UUID id) throws InterruptedException {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Data.Asset a = data.asset(t.orgId(), id);
        if (a.status().equals("PROCESSING")) throw ApiException.conflict("BUSY", "This video is already being processed.");
        MediaConnectionService.Connection c = connections.connected(t.orgId());
        IDirectUpload provider = providers.directUpload(c.provider());
        limits.requireMediaRoom(t.orgId());
        limits.requireProcessingSlot(t.orgId());
        IDirectUpload.Upload u = provider.upload(c.credentials(), appUrl, a.title());
        assets.setUploadReference(t.orgId(), id, c.provider() + "-upload:" + u.id());
        audit.log(t, "asset.upload", "asset", id, a.title() + " (direct upload)");
        return Map.of("url", u.url());
    }

    /**
     * Queues the chosen library videos for broadcast preparation. Each one is checked on its own: it must come from
     * the library, be Draft or Failed here and ready in the library, and fit the project's conversion limit; the rest
     * are reported with a reason, never silently dropped.
     */
    public PrepareResult prepare(Tenant t, List<UUID> ids) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        MediaConnectionService.Connection c = connections.connected(t.orgId());
        providers.require(c.provider(), Capability.SOURCE_FILE);
        limits.requireMediaRoom(t.orgId());
        Map<UUID, Candidate> found = new HashMap<>();
        for (Candidate x : sources.candidates(t.orgId(), new LinkedHashSet<>(ids))) found.put(x.id(), x);
        List<Skipped> skipped = new ArrayList<>();
        int queued = 0;
        for (UUID id : new LinkedHashSet<>(ids)) {
            Candidate x = found.get(id);
            String reason = x == null ? "NOT_FOUND"
                    : !c.provider().equals(x.provider()) ? "NOT_FROM_LIBRARY"
                    : x.status().equals("READY") ? "ALREADY_READY"
                    : x.status().equals("PROCESSING") ? "ALREADY_PROCESSING"
                    : !x.status().equals("DRAFT") && !x.status().equals("FAILED") ? "NOT_FROM_LIBRARY"
                    : !x.providerStatus().equals("READY") ? "NOT_READY_IN_LIBRARY" : null;
            if (reason == null) {
                try {
                    limits.requireProcessingSlot(t.orgId());
                } catch (ApiException e) {
                    reason = "PROCESSING_LIMIT";
                }
            }
            if (reason != null) { skipped.add(new Skipped(id, reason)); continue; }
            assets.markProcessing(t.orgId(), id, PREPARING);
            media.enqueue(t.orgId(), id);
            queued++;
        }
        audit.log(t, "library.prepare", "asset", null, queued + " videos queued for broadcast preparation, " + skipped.size() + " skipped");
        return new PrepareResult(queued, skipped);
    }

    /**
     * Brings one library video into the project's catalogue. Idempotent, so webhook redelivery and re-sync never
     * duplicate rows:
     * <ul>
     *   <li>one of our direct uploads is linked to the row made when its URL was minted, and prepared at once;</li>
     *   <li>any other video ready in the library becomes a new Draft row (catalogue only);</li>
     *   <li>a known video only updates what the library last said about it (asset_source); titles, episodes and rights
     *       edited here are never overwritten.</li>
     * </ul>
     * True when a row was added or linked.
     */
    public boolean apply(UUID org, String provider, RemoteAsset x) {
        return TenantScope.as(org, () -> {
            String ref = provider + ":" + x.externalId(), upload = x.uploadId() == null ? ref : provider + "-upload:" + x.uploadId();
            if (x.status().equals("DELETED")) {
                sources.deleted(org, provider, x.externalId());
                return false;
            }
            if (x.status().equals("ERRORED"))
                assets.markLibraryFailed(org, ref, upload, "The library couldn't process this file. Check that it plays, then upload it again.");
            Optional<UUID> id = x.uploadId() == null || x.status().equals("ERRORED") ? Optional.empty() : assets.linkUploadedAsset(org, upload, ref, CONVERTING);
            id.ifPresent(a -> media.enqueue(org, a));
            if (id.isEmpty() && x.status().equals("READY"))
                id = assets.insertLibraryAsset(org, AssetRules.importedTitle(x.title()), x.durationMs(), CATALOGUED,
                        AssetRules.defaultCues("MOVIE", x.durationMs()), ref);
            sources.seen(org, provider, ref, x);
            return id.isPresent();
        });
    }
}
