package com.faststudio.messaging.v1.media;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.media.MediaJobRepository;
import com.faststudio.v1.repositories.media.MediaJobRepository.Claim;
import com.faststudio.v1.services.media.MediaServiceV1;
import com.faststudio.v1.services.media.MediaServiceV1.Packaged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stage 2 acceptance (docs/MEDIA_JOBS_DESIGN.md "Tests that must pass"): duplicate enqueue, two workers, crash and
 * lease expiry, stale-worker fencing, cancel, retries, fairness and tenant isolation. Background workers are off;
 * the test drives claims itself. Packaging is stubbed (no ffmpeg here); killing a real ffmpeg is MediaStopTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaJobTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-jobs").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
        r.add("faststudio.media.programme-workers", () -> 0);
        r.add("faststudio.media.ad-workers", () -> 0);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired MediaJobRepository jobs;
    @Autowired MediaJobObserverV1 observer;
    @MockitoSpyBean MediaServiceV1 media;

    UUID aurora, northwind;

    @BeforeEach
    void clean() {
        aurora = org("aurora");
        northwind = org("northwind");
        TenantScope.bypass(() -> db.sql("delete from media_job /* all tenants: test reset */").update());
    }

    UUID org(String slug) { return TenantScope.bypass(() -> db.sql("select id from organization where slug = :s").param("s", slug).query(UUID.class).single()); }

    UUID asset(UUID org, String status, String mediaKey) {
        return TenantScope.bypass(() -> db.sql("insert into asset(org_id, title, kind, status, media_key) values (:o, 'Job test', 'MOVIE', :s, :k) returning id")
                .param("o", org).param("s", status).param("k", mediaKey).query(UUID.class).single());
    }

    UUID queued(UUID org, String lane) {
        UUID a = asset(org, "PROCESSING", null);
        TenantScope.as(org, () -> jobs.enqueue(org, a, lane));
        return a;
    }

    Map<String, Object> job(UUID asset) {
        return TenantScope.bypass(() -> db.sql("select * from media_job where asset_id = :a order by created_at desc limit 1").param("a", asset).query().singleRow());
    }

    Map<String, Object> assetRow(UUID asset) {
        return TenantScope.bypass(() -> db.sql("select status, media_key, status_detail from asset where id = :a").param("a", asset).query().singleRow());
    }

    void due(UUID asset) {
        TenantScope.bypass(() -> db.sql("update media_job set next_run_at = now() where asset_id = :a").param("a", asset).update());
    }

    Optional<Claim> claim(String lane, int leaseSeconds) {
        return TenantScope.bypass(() -> jobs.claim(lane, "test", leaseSeconds, 2));
    }

    MvcResult call(MockHttpServletRequestBuilder r, String email) throws Exception {
        UUID user = TenantScope.bypass(() -> db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single());
        return mvc.perform(r.header("Authorization", "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token())).andReturn();
    }

    static Packaged ready(String key) { return new Packaged(key, null, 10_000, "Processed", null, false); }

    @Test
    void duplicateEnqueueGivesOneActiveJob() throws Exception {
        UUID a = asset(aurora, "PROCESSING", null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> TenantScope.as(aurora, () -> { media.enqueue(aurora, a); return null; }));
        for (Future<Object> f : pool.invokeAll(calls)) f.get();
        pool.shutdown();
        assertEquals(1, TenantScope.bypass(() -> db.sql("select count(*) from media_job where asset_id = :a").param("a", a).query(Integer.class).single()));
    }

    @Test
    void twoWorkersRunEachJobExactlyOnce() throws Exception {
        Set<UUID> assets = new HashSet<>();
        for (int i = 0; i < 10; i++) assets.add(queued(i % 2 == 0 ? aurora : northwind, "AD"));
        ConcurrentLinkedQueue<UUID> claimed = new ConcurrentLinkedQueue<>();
        Callable<Object> worker = () -> {
            for (Optional<Claim> c; (c = claim("AD", 60)).isPresent(); ) claimed.add(c.get().assetId());
            return null;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (Future<Object> f : pool.invokeAll(List.of(worker, worker))) f.get();
        pool.shutdown();
        assertEquals(10, claimed.size(), "no job claimed twice");
        assertEquals(assets, new HashSet<>(claimed));
    }

    @Test
    void crashedWorkersJobIsRequeuedAndSucceedsElsewhere() throws Exception {
        UUID a = queued(aurora, "PROGRAMME");
        assertTrue(claim("PROGRAMME", 1).isPresent());   // a worker that then dies: no heartbeats
        Thread.sleep(1_500);
        observer.reap();
        assertEquals("QUEUED", job(a).get("state"));
        assertTrue(((java.sql.Timestamp) job(a).get("next_run_at")).toInstant().isAfter(java.time.Instant.now().plusSeconds(50)), "1-minute backoff");
        due(a);
        doReturn(ready(aurora + "/" + a + ".2")).when(media).pack(aurora, a);
        assertTrue(observer.runOne("PROGRAMME"));
        assertEquals("SUCCEEDED", job(a).get("state"));
        assertEquals(2, job(a).get("attempts"));
        assertEquals("READY", assetRow(a).get("status"));
        assertEquals(aurora + "/" + a + ".2", assetRow(a).get("media_key"));
    }

    @Test
    void staleWorkerCannotPublish() throws Exception {
        UUID a = queued(aurora, "PROGRAMME");
        Claim stale = claim("PROGRAMME", 1).orElseThrow();
        Thread.sleep(1_500);
        observer.reap();
        due(a);
        Claim fresh = claim("PROGRAMME", 60).orElseThrow();
        assertTrue(fresh.fence() > stale.fence());
        assertTrue(TenantScope.as(aurora, () -> jobs.publish(fresh, "new", 5_000, "k/new")));
        assertFalse(TenantScope.as(aurora, () -> jobs.publish(stale, "old", 5_000, "k/old")), "fence blocks the stale worker");
        assertTrue(TenantScope.as(aurora, () -> jobs.fail(stale, "late", true)).isEmpty());
        assertEquals("k/new", assetRow(a).get("media_key"));
        assertEquals("SUCCEEDED", job(a).get("state"));
    }

    @Test
    void cancelQueuedJobKeepsOldMedia() throws Exception {
        UUID fresh = queued(aurora, "PROGRAMME");
        UUID airing = asset(aurora, "PROCESSING", "aurora/old");
        TenantScope.as(aurora, () -> jobs.enqueue(aurora, airing, "PROGRAMME"));
        for (UUID a : List.of(fresh, airing))
            assertEquals(200, call(post("/api/orgs/" + aurora + "/assets/" + a + "/jobs/cancel"), "owner@aurora.test").getResponse().getStatus());
        assertEquals("CANCELLED", job(fresh).get("state"));
        assertEquals("DRAFT", assetRow(fresh).get("status"), "never had media");
        assertEquals("READY", assetRow(airing).get("status"));
        assertEquals("aurora/old", assetRow(airing).get("media_key"), "old media still airs");
        assertEquals(409, call(post("/api/orgs/" + aurora + "/assets/" + fresh + "/jobs/cancel"), "owner@aurora.test").getResponse().getStatus(),
                "nothing left to cancel");
    }

    @Test
    void cancelRunningJobStopsItsWorker() throws Exception {
        UUID a = asset(aurora, "PROCESSING", "aurora/old");
        TenantScope.as(aurora, () -> jobs.enqueue(aurora, a, "PROGRAMME"));
        AtomicBoolean stopped = new AtomicBoolean();
        CountDownLatch packing = new CountDownLatch(1);
        doAnswer(inv -> { stopped.set(true); return null; }).when(media).stop(any());
        doAnswer(inv -> {
            packing.countDown();
            while (!stopped.get()) Thread.sleep(20);   // like ffmpeg until it is killed
            return ready("aurora/half");
        }).when(media).pack(aurora, a);
        Future<Boolean> run = Executors.newSingleThreadExecutor().submit(() -> observer.runOne("PROGRAMME"));
        assertTrue(packing.await(10, TimeUnit.SECONDS));
        assertEquals(200, call(post("/api/orgs/" + aurora + "/assets/" + a + "/jobs/cancel"), "owner@aurora.test").getResponse().getStatus());
        assertEquals("CANCEL_REQUESTED", job(a).get("state"));
        observer.heartbeat();
        assertTrue(run.get(10, TimeUnit.SECONDS));
        assertTrue(stopped.get(), "ffmpeg stop requested");
        assertEquals("CANCELLED", job(a).get("state"));
        assertEquals("READY", assetRow(a).get("status"));
        assertEquals("aurora/old", assetRow(a).get("media_key"), "half-made revision never published");
    }

    @Test
    void retryableFailuresBackOffThenFail() throws Exception {
        UUID a = queued(aurora, "PROGRAMME");
        doReturn(Packaged.failed("Library not ready.", true)).when(media).pack(aurora, a);
        for (int attempt = 1; attempt <= 5; attempt++) {
            due(a);
            assertTrue(observer.runOne("PROGRAMME"));
            Map<String, Object> j = job(a);
            assertEquals(attempt, j.get("attempts"));
            if (attempt < 5) {
                assertEquals("QUEUED", j.get("state"));
                assertEquals("PROCESSING", assetRow(a).get("status"), "still converting while it retries");
                long wait = java.time.Duration.between(java.time.Instant.now(), ((java.sql.Timestamp) j.get("next_run_at")).toInstant()).toMinutes();
                assertEquals((long) Math.pow(5, attempt - 1), wait + 1, 1, "backoff 1, 5, 25, 125 minutes");
            }
        }
        assertEquals("FAILED", job(a).get("state"));
        assertEquals("FAILED", assetRow(a).get("status"));
        assertEquals("Library not ready.", assetRow(a).get("status_detail"));
    }

    @Test
    void permanentFailureFailsAtOnce() throws Exception {
        UUID a = queued(aurora, "PROGRAMME");
        doReturn(Packaged.failed("The video is shorter than 5 seconds.", false)).when(media).pack(aurora, a);
        assertTrue(observer.runOne("PROGRAMME"));
        assertEquals("FAILED", job(a).get("state"));
        assertEquals(1, job(a).get("attempts"));
        assertEquals("FAILED", assetRow(a).get("status"));
    }

    @Test
    void oneBusyProjectDoesNotBlockAnother() {
        for (int i = 0; i < 6; i++) queued(aurora, "PROGRAMME");
        UUID other = queued(northwind, "PROGRAMME");   // queued last
        assertEquals(aurora, claim("PROGRAMME", 60).orElseThrow().orgId());
        assertEquals(aurora, claim("PROGRAMME", 60).orElseThrow().orgId());
        Claim third = claim("PROGRAMME", 60).orElseThrow();
        assertEquals(other, third.assetId(), "aurora is at its 2 running jobs, so northwind goes before aurora's third");
        assertTrue(claim("PROGRAMME", 60).isEmpty(), "both projects at or past their share");
    }

    @Test
    void jobsAreVisibleOnlyInsideTheirProject() throws Exception {
        UUID a = queued(aurora, "PROGRAMME");
        MvcResult mine = call(get("/api/orgs/" + aurora + "/assets/" + a + "/jobs"), "owner@aurora.test");
        assertEquals(200, mine.getResponse().getStatus());
        assertTrue(mine.getResponse().getContentAsString().contains("\"state\":\"QUEUED\""), mine.getResponse().getContentAsString());
        // another project's user, through their own project and through the owner's
        assertEquals(404, call(get("/api/orgs/" + northwind + "/assets/" + a + "/jobs"), "owner@northwind.test").getResponse().getStatus());
        assertEquals(404, call(post("/api/orgs/" + northwind + "/assets/" + a + "/jobs/cancel"), "owner@northwind.test").getResponse().getStatus());
        assertEquals(404, call(post("/api/orgs/" + aurora + "/assets/" + a + "/jobs/cancel"), "owner@northwind.test").getResponse().getStatus(), "not a member");
        assertEquals("QUEUED", job(a).get("state"));
        // row-level security: an unfiltered query in another project's scope sees nothing
        assertEquals(0, TenantScope.as(northwind, () -> db.sql("select count(*) from media_job where asset_id = :a").param("a", a).query(Integer.class).single()));
        assertTrue(TenantScope.as(northwind, () -> jobs.cancel(aurora, a)).isEmpty());
    }
}
