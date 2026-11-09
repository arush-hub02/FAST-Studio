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
import com.faststudio.v1.dto.media.LibraryDto.UploadTarget;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.repositories.media.AssetSourceRepository;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    private final AssetSourceRepository sources;

    public MediaSyncServiceV1(MediaConnectionService connections, MediaConnectionRepository repository, MediaJobRepository jobs,
                              MediaProviderResolverService providers, LibraryServiceV1 library, AssetRules rules, TenantConfigurationServiceV1 configuration,
                              TenantLimitsService limits, AssetRepository assets, TransactionTemplate tx, Audit audit, AssetSourceRepository sources) {
        this.sources = sources;
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
        List<MediaConnectionService.Connection> all = connections.connections(t.orgId());
        if (all.isEmpty()) return new LibraryView(false, null, null, List.of(), status(t.orgId()), List.of(), List.of());
        MediaConnectionService.Connection c = all.getFirst();
        List<String> caps = all.stream().flatMap(x -> providers.get(x.provider()).capabilities().stream()).map(Enum::name).distinct().sorted().toList();
        List<UUID> waiting = all.stream().map(MediaConnectionService.Connection::provider).distinct()
                .flatMap(p -> sources.waiting(t.orgId(), p).stream()).toList();
        List<MediaConnectionService.Connection> up = connections.usable(t.orgId()).stream()
                .filter(x -> providers.supports(x.provider(), Capability.DIRECT_UPLOAD)).toList();
        // white-label: a connection still named after its platform shows as "Video library"; a name the platform administrator gave shows as is
        List<UploadTarget> uploadTo = new ArrayList<>();
        for (int i = 0; i < up.size(); i++) {
            MediaConnectionService.Connection x = up.get(i);
            boolean own = x.name() != null && !x.name().isBlank() && !x.name().trim().equalsIgnoreCase(x.provider());
            uploadTo.add(new UploadTarget(x.id(), own ? x.name().trim() : up.size() == 1 ? "Video library" : "Video library " + (i + 1)));
        }
        return new LibraryView(true, c.checkedAt() == null ? null : c.checkError() == null, c.checkedAt(), caps, status(t.orgId()), waiting, uploadTo);
    }

    /** Queues a full scan of every usable catalogue connection, unless one is already queued or running (then it just reports). */
    private boolean start(UUID org) {
        // same order as before Stage 3: the project's own limits answer first, then the connection
        // ponytail: checked once before the sync, so one sync can go past the media limit; check per video if that matters
        limits.requireMediaRoom(org);
        rules.kindOf(org, "MOVIE");   // library videos arrive as movies; fails clearly if the project has that type switched off
        connections.connected(org);   // none, or the connector switched off: a clear error
        List<MediaConnectionService.Connection> scan = connections.usable(org).stream()
                .filter(c -> providers.supports(c.provider(), Capability.CATALOGUE)).toList();
        if (scan.isEmpty()) providers.require(connections.connected(org).provider(), Capability.CATALOGUE);
        boolean started = false;
        for (MediaConnectionService.Connection c : scan)
            started |= Boolean.TRUE.equals(TenantScope.as(org, () -> tx.execute(s -> {
                boolean added = jobs.enqueueSync(org, c.id());
                if (added) repository.startSync(org, c.id());
                return added;
            })));
        return started;
    }

    /** The project's sync across its connections: running while any runs; counts added up; finished when every one finished. */
    private SyncStatus status(UUID org) {
        return TenantScope.as(org, () -> {
            String state = null, error = null;
            int videos = 0, added = 0, pages = 0;
            boolean finished = true;
            OffsetDateTime started = null, done = null, next = null;
            for (MediaConnectionService.Connection c : connections.connections(org)) {
                JobView job = jobs.latestSync(org, c.id()).orElse(null);
                SyncRow s = repository.sync(org, c.id()).orElse(null);
                if (job == null || s == null) continue;
                boolean active = job.state().equals("QUEUED") || job.state().equals("RUNNING");
                if (state == null || active) state = job.state();
                videos += s.videos(); added += s.added(); pages += s.pages();
                finished &= s.finishedAt() != null;
                if (started == null || (s.startedAt() != null && s.startedAt().isBefore(started))) started = s.startedAt();
                if (s.finishedAt() != null && (done == null || s.finishedAt().isAfter(done))) done = s.finishedAt();
                if (error == null) error = job.lastError();
                if (job.nextRunAt() != null) next = job.nextRunAt();
            }
            if (state == null) return new SyncStatus("NEVER", 0, 0, 0, false, null, null, null, null);
            return new SyncStatus(state, videos, added, pages, finished, started, finished ? done : null, error, next);
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
        MediaConnectionService.Connection c = connections.connected(org, job.connectionId());
        IMediaProvider provider = providers.get(c.provider());
        String next = repository.sync(org, c.id()).map(SyncRow::cursor).orElse(null);
        do {
            if (stop.getAsBoolean()) return;
            IMediaProvider.Page page;
            try {
                page = provider.page(c.credentials(), next);
            } catch (ApiException e) {
                throw new MediaServiceV1.Retryable(e.getMessage());   // tenant-safe already; the scan continues from this page later
            }
            int added = 0;
            for (RemoteAsset x : page.assets()) if (library.apply(org, c, x)) added++;
            next = page.next();
            // ponytail: a crash between the page and its checkpoint re-reads that page; imports are idempotent, only "videos" counts it twice
            if (!repository.checkpoint(job, next, page.assets().size(), added)) return;
        } while (next != null);
        repository.completeSync(job, c.provider());
    }
}
