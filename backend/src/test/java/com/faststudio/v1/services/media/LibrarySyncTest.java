package com.faststudio.v1.services.media;

import com.faststudio.messaging.v1.media.MediaJobObserverV1;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.util.mediaProvider.Capability;
import com.faststudio.v1.util.mediaProvider.IMediaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Ingestion Stage 3 against a fake video platform that can only list its catalogue (two videos per page): the provider
 * layer works without Mux and refuses what the platform can't do; a sync interrupted by an outage resumes from its
 * saved page; re-sync keeps edits made here and flags videos that left the library; "prepare selected" queues only
 * eligible videos and says why for the rest. Background workers are off; the test runs the jobs itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LibrarySyncTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-sync").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
        r.add("faststudio.media.programme-workers", () -> 0);
        r.add("faststudio.media.ad-workers", () -> 0);
    }

    static final FakeProvider FAKE = new FakeProvider();

    @TestConfiguration
    static class Providers {
        @Bean
        IMediaProvider fakeProvider() { return FAKE; }
    }

    /** A catalogue-only platform: pages of two, checkpoints are list offsets, and it can be told to fail once at an offset. */
    static class FakeProvider implements IMediaProvider {
        volatile List<RemoteAsset> catalogue = List.of();
        volatile Set<Capability> caps = EnumSet.of(Capability.CATALOGUE);
        volatile String failOnceAt;
        final Map<String, Integer> reads = new ConcurrentHashMap<>();

        public String key() { return "fake"; }

        public Set<Capability> capabilities() { return caps; }

        public void check(Credentials c) {}

        public Page page(Credentials c, String checkpoint) {
            String at = checkpoint == null ? "0" : checkpoint;
            reads.merge(at, 1, Integer::sum);
            if (at.equals(failOnceAt)) {
                failOnceAt = null;
                throw new ApiException(HttpStatus.BAD_GATEWAY, "LIBRARY_UNAVAILABLE", "The video library couldn't be reached. Try again later.");
            }
            int from = Integer.parseInt(at), to = Math.min(from + 2, catalogue.size());
            return new Page(catalogue.subList(from, to), to < catalogue.size() ? String.valueOf(to) : null);
        }
    }

    static IMediaProvider.RemoteAsset video(String id, String status) { return new IMediaProvider.RemoteAsset(id, status, "Film " + id, 600_000, null); }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired JsonMapper json;
    @Autowired Secrets secrets;
    @Autowired MediaJobObserverV1 observer;
    @Autowired LibraryServiceV1 library;

    UUID org;

    @BeforeEach
    void connectFakeLibrary() {
        org = TenantScope.bypass(() -> db.sql("select id from organization where slug = 'northwind'").query(UUID.class).single());
        FAKE.catalogue = List.of();
        FAKE.caps = EnumSet.of(Capability.CATALOGUE);
        FAKE.reads.clear();
        TenantScope.bypass(() -> {
            db.sql("delete from media_job /* all tenants: test reset */").update();
            db.sql("delete from asset where org_id = :o and source_ref like 'fake:%'").param("o", org).update();
            db.sql("insert into connector_definition(key, name, category) values ('fake', 'Fake library', 'MEDIA') on conflict do nothing").update();
            db.sql("insert into tenant_connector(tenant_id, connector_key) select tenant_id, 'fake' from organization where id = :o on conflict do nothing")
                    .param("o", org).update();
            db.sql("update organization set features = 'LIBRARY_SYNC' where id = :o").param("o", org).update();
            db.sql("delete from media_connection where org_id = :o").param("o", org).update();
            return db.sql("insert into media_connection(org_id, provider, token_id, token_secret) values (:o, 'fake', :i, :s)")
                    .param("o", org).param("i", secrets.seal("id")).param("s", secrets.seal("secret")).update();
        });
    }

    MvcResult call(MockHttpServletRequestBuilder r, String email, String body) throws Exception {
        UUID user = TenantScope.bypass(() -> db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single());
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token())).andReturn();
    }

    JsonNode status() throws Exception {
        return json.readTree(call(get("/api/orgs/" + org + "/library"), "owner@northwind.test", null).getResponse().getContentAsString()).path("sync");
    }

    /** Starts a sync through the API and runs its job once on this thread. */
    JsonNode syncOnce() throws Exception {
        MvcResult r = call(post("/api/orgs/" + org + "/library/sync"), "owner@northwind.test", null);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertTrue(observer.runOne("PROGRAMME"));
        return status();
    }

    Map<String, Object> asset(String externalId) {
        return TenantScope.bypass(() -> db.sql("""
                select a.id, a.title, a.status, s.provider_status, s.title as source_title from asset a
                left join asset_source s on s.org_id = a.org_id and s.asset_id = a.id where a.org_id = :o and a.source_ref = :r""")
                .param("o", org).param("r", "fake:" + externalId).query().singleRow());
    }

    int imported() {
        return TenantScope.bypass(() -> db.sql("select count(*) from asset where org_id = :o and source_ref like 'fake:%'").param("o", org).query(Integer.class).single());
    }

    @Test
    void aCatalogueOnlyPlatformSyncsWithoutMuxAndRefusesWhatItCantDo() throws Exception {
        FAKE.catalogue = List.of(video("a", "READY"), video("b", "READY"), video("c", "PREPARING"), video("d", "READY"));
        JsonNode s = syncOnce();
        assertEquals("SUCCEEDED", s.path("state").asString(), s.toString());
        assertEquals(4, s.path("videos").asInt());
        assertEquals(3, s.path("added").asInt(), "the video still preparing on the platform waits for a later sync");
        assertEquals(2, s.path("pages").asInt());
        assertTrue(s.path("complete").asBoolean());
        assertEquals(3, imported());
        assertEquals("DRAFT", asset("a").get("status"), "catalogue only");

        assertEquals(200, call(post("/api/orgs/" + org + "/library/sync"), "owner@northwind.test", null).getResponse().getStatus());
        assertEquals(200, call(post("/api/orgs/" + org + "/library/sync"), "owner@northwind.test", null).getResponse().getStatus());
        assertEquals(1, TenantScope.bypass(() -> db.sql("select count(*) from media_job where org_id = :o and kind = 'SYNC' and state = 'QUEUED'")
                .param("o", org).query(Integer.class).single()), "a second click joins the queued sync");

        String view = call(get("/api/orgs/" + org + "/library"), "owner@northwind.test", null).getResponse().getContentAsString();
        assertTrue(view.contains("\"capabilities\":[\"CATALOGUE\"]"), view);
        MvcResult prepare = call(post("/api/orgs/" + org + "/library/prepare"), "owner@northwind.test", "{\"assetIds\":[\"" + asset("a").get("id") + "\"]}");
        assertEquals(409, prepare.getResponse().getStatus());
        assertTrue(prepare.getResponse().getContentAsString().contains("NOT_SUPPORTED"));
        MvcResult upload = call(post("/api/orgs/" + org + "/assets/" + asset("a").get("id") + "/upload-url"), "owner@northwind.test", null);
        assertEquals(409, upload.getResponse().getStatus());
        assertTrue(upload.getResponse().getContentAsString().contains("NOT_SUPPORTED"));
    }

    @Test
    void anInterruptedSyncResumesFromItsSavedPage() throws Exception {
        FAKE.catalogue = List.of(video("a", "READY"), video("b", "READY"), video("c", "READY"), video("d", "READY"), video("e", "READY"));
        FAKE.failOnceAt = "2";                                       // the platform is down when page 2 is asked for
        JsonNode s = syncOnce();
        assertEquals("QUEUED", s.path("state").asString(), "waits to retry: " + s);
        assertTrue(s.path("lastError").asString().contains("couldn't be reached"), s.toString());
        assertFalse(s.path("nextRunAt").isNull());
        assertEquals(2, imported(), "the first page is kept");
        TenantScope.bypass(() -> db.sql("update media_job set next_run_at = now() where org_id = :o").param("o", org).update());
        assertTrue(observer.runOne("PROGRAMME"));
        s = status();
        assertEquals("SUCCEEDED", s.path("state").asString(), s.toString());
        assertEquals(5, imported());
        assertEquals(Map.of("0", 1, "2", 2, "4", 1), FAKE.reads, "page 1 is not read again; the failed page is retried once");
    }

    @Test
    void reSyncKeepsLocalEditsAndFlagsVideosThatLeftTheLibrary() throws Exception {
        FAKE.catalogue = List.of(video("a", "READY"), video("b", "READY"));
        syncOnce();
        String a = asset("a").get("id").toString();
        assertEquals(200, call(patch("/api/orgs/" + org + "/assets/" + a), "owner@northwind.test", "{\"title\":\"Our own title\"}").getResponse().getStatus());

        FAKE.catalogue = List.of(new IMediaProvider.RemoteAsset("a", "READY", "Renamed on the platform", 600_000, null));   // b was removed there
        assertEquals("SUCCEEDED", syncOnce().path("state").asString());
        assertEquals("Our own title", asset("a").get("title"), "edits made here survive a re-sync");
        assertEquals("Renamed on the platform", asset("a").get("source_title"), "what the platform says is still recorded");
        assertEquals("MISSING", asset("b").get("provider_status"));
        assertEquals("DRAFT", asset("b").get("status"), "the local video stays");

        library.apply(org, "fake", video("a", "DELETED"));
        assertEquals("DELETED", asset("a").get("provider_status"));
        assertEquals(2, imported(), "nothing is deleted here");
    }

    @Test
    void prepareSelectedQueuesOnlyEligibleVideosAndSaysWhyForTheRest() throws Exception {
        FAKE.caps = EnumSet.of(Capability.CATALOGUE, Capability.SOURCE_FILE);
        FAKE.catalogue = List.of(video("ok", "READY"), video("broken", "READY"), video("aired", "READY"));
        syncOnce();
        library.apply(org, "fake", video("broken", "ERRORED"));                      // the platform lost it since
        TenantScope.bypass(() -> db.sql("update asset set status = 'READY' where org_id = :o and source_ref = 'fake:aired'").param("o", org).update());
        UUID local = TenantScope.bypass(() -> db.sql("insert into asset(org_id, title, kind) values (:o, 'Local', 'MOVIE') returning id")
                .param("o", org).query(UUID.class).single());
        UUID unknown = UUID.randomUUID();
        Object ok = asset("ok").get("id"), broken = asset("broken").get("id"), aired = asset("aired").get("id");
        String body = "{\"assetIds\":[\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"]}".formatted(ok, broken, aired, local, unknown);

        // the Aurora owner is a read-only analyst in Northwind (demo data)
        assertEquals(403, call(post("/api/orgs/" + org + "/library/prepare"), "owner@aurora.test", body).getResponse().getStatus(), "needs asset editing");
        MvcResult r = call(post("/api/orgs/" + org + "/library/prepare"), "owner@northwind.test", body);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode result = json.readTree(r.getResponse().getContentAsString());
        assertEquals(1, result.path("queued").asInt());
        Map<String, String> reasons = new HashMap<>();
        result.path("skipped").forEach(x -> reasons.put(x.path("assetId").asString(), x.path("reason").asString()));
        assertEquals(Map.of(broken.toString(), "NOT_READY_IN_LIBRARY", aired.toString(), "ALREADY_READY", local.toString(), "NOT_FROM_LIBRARY",
                unknown.toString(), "NOT_FOUND"), reasons);
        assertEquals("PROCESSING", asset("ok").get("status"));
        assertEquals(1, TenantScope.bypass(() -> db.sql("select count(*) from media_job where org_id = :o and kind = 'PACKAGE' and state = 'QUEUED'")
                .param("o", org).query(Integer.class).single()));

        MvcResult foreign = call(post("/api/orgs/" + org + "/library/prepare"), "operator@aurora.test", body);
        assertEquals(404, foreign.getResponse().getStatus(), "another project's user can't reach it");
    }
}
