package com.faststudio.messaging.v1.media;

import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.media.MediaJobRepository;
import com.faststudio.v1.repositories.media.MediaJobRepository.Claim;
import com.faststudio.v1.services.media.MediaServiceV1;
import com.faststudio.v1.services.media.MediaServiceV1.Packaged;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pull consumer of media_job (docs/MEDIA_JOBS_DESIGN.md). Worker threads per lane poll the table; any number of servers
 * can, because a claim takes one row with FOR UPDATE SKIP LOCKED. Claims, heartbeats and the reaper are system work
 * (system login); each job then runs entirely inside its project's scope. A heartbeat that finds the job cancelled or
 * taken over kills its ffmpeg, and such a worker never publishes: every result is written under the job's fence.
 */
@Component
public class MediaJobObserverV1 {
    private static final Logger log = LoggerFactory.getLogger(MediaJobObserverV1.class);
    static final long POLL_MS = 2_000;

    private final MediaJobRepository jobs;
    private final MediaServiceV1 media;
    private final int programmeWorkers, adWorkers, leaseSeconds, perProject;
    private final String node = ManagementFactory.getRuntimeMXBean().getName();   // pid@host
    private final Map<UUID, Running> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("media-heartbeat").unstarted(r));
    private volatile boolean stopping;

    /** A job this server is running; {@code stop} is CANCEL or LOST once a heartbeat says it must not publish. */
    static final class Running {
        final Claim claim;
        final Thread worker;
        String stop;
        boolean done;

        Running(Claim claim, Thread worker) {
            this.claim = claim;
            this.worker = worker;
        }
    }

    public MediaJobObserverV1(MediaJobRepository jobs, MediaServiceV1 media,
                              @Value("${faststudio.media.programme-workers:2}") int programmeWorkers,
                              @Value("${faststudio.media.ad-workers:1}") int adWorkers,
                              @Value("${faststudio.media.lease-seconds:120}") int leaseSeconds,
                              @Value("${faststudio.media.jobs-per-project:2}") int perProject) {
        this.jobs = jobs;
        this.media = media;
        this.programmeWorkers = programmeWorkers;
        this.adWorkers = adWorkers;
        this.leaseSeconds = leaseSeconds;
        this.perProject = perProject;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        for (int i = 0; i < programmeWorkers; i++) Thread.ofPlatform().daemon().name("media-programme-" + i).start(() -> loop("PROGRAMME"));
        for (int i = 0; i < adWorkers; i++) Thread.ofPlatform().daemon().name("media-ad-" + i).start(() -> loop("AD"));
        long beat = Math.max(1, leaseSeconds * 1000L / 4);   // 30 s for the default 2-minute lease
        heartbeats.scheduleWithFixedDelay(this::heartbeat, beat, beat, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;   // running jobs keep their lease until it runs out; the reaper re-queues them
        heartbeats.shutdownNow();
    }

    private void loop(String lane) {
        while (!stopping) {
            try {
                if (!runOne(lane)) Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (stopping) return;
                log.warn("Media worker for {} could not poll: {}", lane, e.toString());
                try { Thread.sleep(POLL_MS); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** Claims and runs one due job of the lane on this thread; false when none was due. */
    public boolean runOne(String lane) {
        Optional<Claim> next = TenantScope.bypass(() -> jobs.claim(lane, node + "/" + Thread.currentThread().getName(), leaseSeconds, perProject));
        if (next.isEmpty()) return false;
        Running r = new Running(next.get(), Thread.currentThread());
        active.put(r.claim.id(), r);
        try {
            TenantScope.as(r.claim.orgId(), () -> { execute(r); return null; });
        } finally {
            active.remove(r.claim.id());
            synchronized (r) { r.done = true; }
            media.release(r.worker);
        }
        return true;
    }

    private void execute(Running r) {
        Claim c = r.claim;
        Packaged p;
        try {
            p = media.pack(c.orgId(), c.assetId());
        } catch (Exception e) {
            log.error("Media job {} for asset {} failed", c.id(), c.assetId(), e);
            p = Packaged.failed("Unexpected processing error.", true);
        }
        try {
            String stop;
            synchronized (r) { stop = r.stop; }
            if (stop != null) {                        // cancelled or taken over: never publish
                if (p.mediaKey() != null) media.discard(p.mediaKey());
                jobs.cancelled(c);                     // no-op unless it was a cancel
                return;
            }
            if (p.error() != null) {
                if (jobs.fail(c, p.error(), p.retry()).isEmpty()) jobs.cancelled(c);
            } else if (jobs.publish(c, p.detail(), p.durationMs(), p.mediaKey())) {
                media.retire(p);
            } else {                                   // cancelled or taken over meanwhile: the asset keeps what it has
                media.discard(p.mediaKey());
                jobs.cancelled(c);
            }
        } catch (Exception e) {
            log.error("Media job {} could not record its result", c.id(), e);   // the lease runs out and the reaper retries it
        }
    }

    /** Extends the lease of every running job; stops the ones that were cancelled or are no longer ours. */
    void heartbeat() {
        for (Running r : active.values()) {
            try {
                Optional<String> state = TenantScope.as(r.claim.orgId(), () -> jobs.heartbeat(r.claim, leaseSeconds));
                if (state.isPresent() && state.get().equals("RUNNING")) continue;
                synchronized (r) {
                    if (r.done || r.stop != null) continue;
                    r.stop = state.isPresent() ? "CANCEL" : "LOST";
                    media.stop(r.worker);
                }
                log.info("Media job {} stopped: {}", r.claim.id(), r.stop);
            } catch (Exception e) {
                log.warn("Heartbeat for media job {} failed: {}", r.claim.id(), e.toString());
            }
        }
    }

    /** Jobs whose server stopped heartbeating go back to the queue (or fail after their last attempt). */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void reap() {
        int n = TenantScope.bypass(jobs::reap);
        if (n > 0) log.info("Re-queued or closed {} media jobs whose server stopped", n);
    }

    @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
    public void purge() {
        TenantScope.bypass(jobs::purge);
    }
}
