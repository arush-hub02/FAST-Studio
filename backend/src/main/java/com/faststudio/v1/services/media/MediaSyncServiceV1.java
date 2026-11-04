package com.faststudio.v1.services.media;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.AssetDto.JobView;
import com.faststudio.v1.dto.media.LibraryDto.LibraryView;
import com.faststudio.v1.dto.media.LibraryDto.SyncStatus;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.repositories.media.MediaConnectionRepository;
import com.faststudio.v1.repositories.media.MediaConnectionRepository.SyncRow;
import com.faststudio.v1.repositories.media.MediaJobRepository;
import com.faststudio.v1.repositories.media.MediaJobRepository.Claim;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.services.tenancy.TenantLimitsService;
import com.faststudio.v1.util.mediaProvider.Capability;
import com.faststudio.v1.util.mediaProvider.IMediaProvider;
import com.faststudio.v1.util.mediaProvider.IMediaProvider.RemoteAsset;
import com.faststudio.v1.util.mediaProvider.MediaProviderResolverService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Library sync (ingestion Stage 3): imports the connected library's catalogue as Draft videos, without preparing them
 * (preparation is an explicit choice, see {@link LibraryServiceV1#prepare}). A sync is a durable SYNC media job: the
 * worker reads one page at a time and saves what it did plus where to continue after each page, so a crash, restart
 * or retry continues from the last saved page. Nothing is held open while the platform is called.
 */
@Service
public class MediaSyncServiceV1 {
    private final MediaConnectionService connections;
    private final MediaConnectionRepository repository;
    private final MediaJobRepository jobs;
    private final MediaProviderResolverService providers;
    private final LibraryServiceV1 library;
    private final AssetRules rules;
    private final TenantConfigurationServiceV1 configuration;
    private final TenantLimitsService limits;
    private final AssetRepository assets;
    private final TransactionTemplate tx;
    private final Audit audit;

    public MediaSyncServiceV1(MediaConnectionService connections, MediaConnectionRepository repository, MediaJobRepository jobs,
                              MediaProviderResolverService providers, LibraryServiceV1 library, AssetRules rules, TenantConfigurationServiceV1 configuration,
                              TenantLimitsService limits, AssetRepository assets, TransactionTemplate tx, Audit audit) {
        this.connections = connections;
        this.repository = repository;
        this.jobs = jobs;
        this.providers = providers;
        this.library = library;
        this.rules = rules;
        this.configuration = configuration;
        this.limits = limits;
        this.assets = assets;
        this.tx = tx;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ starting and watching

    /** Projects sync themselves only with the LIBRARY_SYNC feature; otherwise the platform syncs from its Connectors page. */
    public SyncStatus sync(Tenant t) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        requireFeature(t.orgId());
        boolean started = start(t.orgId());
        audit.log(t, "library.sync", "asset", null, started ? "library sync started" : "library sync already running");
        return status(t.orgId());
    }

    /** Platform administrators sync any customer project. */
    public SyncStatus platformSync(UUID org) {
        connections.requireCustomer(org);
        boolean started = start(org);
        audit.platform(Auth.userId(), "platform.mux.sync", "organization", org, started ? "library sync started" : "library sync already running");
        return status(org);
    }

    public SyncStatus platformStatus(UUID org) {
        connections.requireCustomer(org);
        return status(org);
    }

    /** What the project sees of its library: connected, usable, what it can do, and the sync. Never names the provider. */
    public LibraryView library(Tenant t) {
        MediaConnectionService.Connection c = connections.connection(t.orgId());
        if (c == null) return new LibraryView(false, null, null, List.of(), status(t.orgId()));
        List<String> caps = providers.get(c.provider()).capabilities().stream().map(Enum::name).sorted().toList();
        return new LibraryView(true, c.checkedAt() == null ? null : c.checkError() == null, c.checkedAt(), caps, status(t.orgId()));
    }

    /** Queues a full scan from the first page, unless one is already queued or running (then it just reports). */
    private boolean start(UUID org) {
        // same order as before Stage 3: the project's own limits answer first, then the connection
        // ponytail: checked once before the sync, so one sync can go past the media limit; check per video if that matters
        limits.requireMediaRoom(org);
        rules.kindOf(org, "MOVIE");   // library videos arrive as movies; fails clearly if the project has that type switched off
        MediaConnectionService.Connection c = connections.connected(org);
        providers.require(c.provider(), Capability.CATALOGUE);
        return Boolean.TRUE.equals(TenantScope.as(org, () -> tx.execute(s -> {
            boolean added = jobs.enqueueSync(org);
            if (added) repository.startSync(org);
            return added;
        })));
    }

    private SyncStatus status(UUID org) {
        return TenantScope.as(org, () -> {
            JobView job = jobs.latestSync(org).orElse(null);
            SyncRow s = repository.sync(org).orElse(null);
            if (job == null || s == null) return new SyncStatus("NEVER", 0, 0, 0, false, null, null, null, null);
            return new SyncStatus(job.state(), s.videos(), s.added(), s.pages(), s.finishedAt() != null, s.startedAt(), s.finishedAt(),
                    job.lastError(), job.nextRunAt());
        });
    }

    private void requireFeature(UUID org) {
        if (!configuration.effectiveFeatures(org, assets.projectFeatures(org)).contains(Feature.LIBRARY_SYNC.name()))
            throw new ApiException(HttpStatus.FORBIDDEN, "FEATURE_OFF", "This isn't switched on for your company. Ask the platform administrator.");
    }

    // ------------------------------------------------------------------ the job (MediaJobObserverV1, inside the project's scope)

    /**
     * Runs or resumes the claimed SYNC job from its checkpoint, page by page, until the last page; then completes the
     * job and flags library videos that weren't seen as MISSING. Returns early, without completing, when {@code stop}
     * says the job was cancelled or taken over, or when a checkpoint can't be saved because the fence moved on.
     * A platform outage throws {@link MediaServiceV1.Retryable}: the job retries later from the saved page.
     */
    public void run(Claim job, BooleanSupplier stop) throws Exception {
        UUID org = job.orgId();
        MediaConnectionService.Connection c = connections.connected(org);
        IMediaProvider provider = providers.get(c.provider());
        String next = repository.sync(org).map(SyncRow::cursor).orElse(null);
        do {
            if (stop.getAsBoolean()) return;
            IMediaProvider.Page page;
            try {
                page = provider.page(c.credentials(), next);
            } catch (ApiException e) {
                throw new MediaServiceV1.Retryable(e.getMessage());   // tenant-safe already; the scan continues from this page later
            }
            int added = 0;
            for (RemoteAsset x : page.assets()) if (library.apply(org, c.provider(), x)) added++;
            next = page.next();
            // ponytail: a crash between the page and its checkpoint re-reads that page; imports are idempotent, only "videos" counts it twice
            if (!repository.checkpoint(job, next, page.assets().size(), added)) return;
        } while (next != null);
        repository.completeSync(job, c.provider());
    }
}
