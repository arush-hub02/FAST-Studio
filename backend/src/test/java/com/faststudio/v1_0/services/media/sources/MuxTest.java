package com.faststudio.v1_0.services.media.sources;

import com.faststudio.v1_0.core.auth.Tokens;
import com.faststudio.v1_0.core.tenant.TenantScope;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The video library connector against recorded Mux API JSON (src/test/resources/mux), served by a local stub that
 * only answers with the tenant's credentials: encrypted account storage, paginated catalogue sync into asset rows,
 * direct upload, signed webhooks, and white-labelling (tenants never see the provider).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MuxTest {
    static final String TOKEN = "tok-id-1234", SECRET = "tok-secret-do-not-leak", HOOK = "whsec-test-signing-secret";
    static final AtomicReference<String> uploadBody = new AtomicReference<>();
    static HttpServer mux;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-mux").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
        mux = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mux.createContext("/video/v1/", MuxTest::stub);
        mux.start();
        r.add("faststudio.mux.api-url", () -> "http://127.0.0.1:" + mux.getAddress().getPort());
    }

    @AfterAll
    static void stop() { mux.stop(0); }

    static void stub(HttpExchange x) throws IOException {
        String auth = "Basic " + Base64.getEncoder().encodeToString((TOKEN + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
        String path = x.getRequestURI().getPath(), q = Objects.toString(x.getRequestURI().getRawQuery(), "");
        if (path.equals("/video/v1/uploads")) uploadBody.set(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String file = path.equals("/video/v1/assets") && q.contains("page=1") ? "assets-page1.json"
                : path.equals("/video/v1/assets") && q.contains("cursor=eyJwYWdlIjoyfQ") ? "assets-page2.json"
                : path.equals("/video/v1/uploads") && x.getRequestMethod().equals("POST") ? "upload.json" : null;
        int status = !auth.equals(x.getRequestHeaders().getFirst("Authorization")) ? 401 : file == null ? 404 : 200;
        byte[] b = status == 200 ? MuxTest.class.getResourceAsStream("/mux/" + file).readAllBytes() : "{\"error\":{}}".getBytes();
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired tools.jackson.databind.json.JsonMapper json;

    UUID id(String sql) { return db.sql(sql).query(UUID.class).single(); }

    UUID aurora() { return id("select id from organization where slug = 'aurora'"); }

    String bearer(String email) {
        UUID user = db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single();
        return "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token();
    }

    MvcResult call(MockHttpServletRequestBuilder req, String as, String body) throws Exception {
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req.header("Authorization", bearer(as))).andReturn();
    }

    List<Map<String, Object>> libraryRows(UUID org) {
        return TenantScope.bypass(() -> db.sql("select title, kind, duration_ms, source_ref from asset where org_id = :o and source_ref like 'mux%' order by title")
                .param("o", org).query().listOfRows());
    }

    @Test @Order(1)
    void platformAdminConnectsTheLibraryAndSecretsStayEncrypted() throws Exception {
        String path = "/api/platform/orgs/" + aurora() + "/connectors/mux";
        MvcResult r = call(put(path), "admin@platform.test", """
                {"tokenId":"%s","tokenSecret":"%s","webhookSecret":"%s","environment":"Production","passthroughTag":"fast"}""".formatted(TOKEN, SECRET, HOOK));
        String text = r.getResponse().getContentAsString();
        assertEquals(200, r.getResponse().getStatus(), text);
        assertTrue(text.contains("\"connected\":true") && text.contains("\"webhookSecretSet\":true") && text.contains("/hooks/mux/" + aurora()), text);
        for (String s : List.of(TOKEN, SECRET, HOOK)) assertFalse(text.contains(s), "the answer never carries credentials");
        String stored = TenantScope.bypass(() -> db.sql("select token_id || token_secret || webhook_secret from mux_account where org_id = :o")
                .param("o", aurora()).query(String.class).single());
        for (String s : List.of(TOKEN, SECRET, HOOK)) assertFalse(stored.contains(s), "credentials are encrypted at rest");
        // blank token id and secrets keep the stored ones (the sync below still authenticates)
        assertEquals(200, call(put(path), "admin@platform.test", "{\"environment\":\"Prod\",\"passthroughTag\":\"fast\"}").getResponse().getStatus());
        assertEquals(403, call(get(path), "owner@aurora.test", null).getResponse().getStatus(), "tenants never reach the account");
    }

    @Test @Order(2)
    void syncImportsEachTaggedReadyVideoOnce() throws Exception {
        String path = "/api/orgs/" + aurora() + "/library/sync";
        assertEquals(403, call(post(path), "owner@aurora.test", null).getResponse().getStatus(), "companies sync only with the LIBRARY_SYNC feature");
        MvcResult r = call(post("/api/platform/orgs/" + aurora() + "/connectors/mux/sync"), "admin@platform.test", null);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("{\"videos\":4,\"added\":2}", r.getResponse().getContentAsString(), "4 tagged across both pages; only the 2 ready ones import");
        List<Map<String, Object>> rows = libraryRows(aurora());
        assertEquals(List.of("Ocean Tales: Episode 1", "Untitled video"), rows.stream().map(m -> m.get("title")).toList());
        assertEquals(List.of(1_320_000L, 30_000L), rows.stream().map(m -> ((Number) m.get("duration_ms")).longValue()).toList(), "whole 5 s segments");
        assertEquals(List.of("mux:Kp01aBcDeFgH", "mux:Nt2NoTitle"), rows.stream().map(m -> m.get("source_ref")).toList());
        assertEquals(Set.of("MOVIE"), rows.stream().map(m -> m.get("kind")).collect(java.util.stream.Collectors.toSet()));

        db.sql("update organization set features = 'LIBRARY_SYNC' where slug in ('aurora', 'northwind')").update();
        assertEquals("{\"videos\":4,\"added\":0}", call(post(path), "owner@aurora.test", null).getResponse().getContentAsString(), "re-sync adds nothing");
        assertEquals(403, call(post(path), "operator@aurora.test", null).getResponse().getStatus(), "needs ASSETS_EDIT");

        UUID northwind = id("select id from organization where slug = 'northwind'");
        MvcResult other = call(post("/api/orgs/" + northwind + "/library/sync"), "owner@northwind.test", null);
        assertEquals(409, other.getResponse().getStatus());
        String library = call(get("/api/orgs/" + aurora() + "/assets"), "owner@aurora.test", null).getResponse().getContentAsString();
        for (String text : List.of(other.getResponse().getContentAsString(), library))
            assertFalse(text.toLowerCase().contains("mux"), "white-label: tenants never see the provider: " + text);
    }

    @Test @Order(3)
    void directUploadBecomesReadyThroughTheSignedWebhook() throws Exception {
        UUID org = aurora();
        MvcResult created = call(post("/api/orgs/" + org + "/assets"), "owner@aurora.test", "{\"title\":\"Launch trailer\",\"kind\":\"SHORT\"}");
        UUID asset = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("asset").path("id").asString());
        MvcResult u = call(post("/api/orgs/" + org + "/assets/" + asset + "/upload-url"), "owner@aurora.test", null);
        assertEquals(200, u.getResponse().getStatus(), u.getResponse().getContentAsString());
        assertTrue(u.getResponse().getContentAsString().contains("https://storage.googleapis.com/"));
        String sent = uploadBody.get();
        assertTrue(sent.contains("\"passthrough\":\"fast\"") && sent.contains("\"master_access\":\"temporary\"") && sent.contains("\"cors_origin\":\"http://localhost:8080\""), sent);
        assertEquals("mux-upload:Up1oadAbc", sourceRef(asset));

        byte[] event = MuxTest.class.getResourceAsStream("/mux/webhook-asset-ready.json").readAllBytes();
        long now = Instant.now().getEpochSecond();
        assertEquals(401, hook(org, event, null), "unsigned");
        assertEquals(401, hook(org, event, sign("another-secret", now, event)), "wrong secret");
        assertEquals(401, hook(org, event, sign(HOOK, now - 600, event)), "stale timestamp");
        UUID northwind = id("select id from organization where slug = 'northwind'");
        assertEquals(404, hook(northwind, event, sign(HOOK, now, event)), "another tenant's endpoint has no account");
        assertEquals("mux-upload:Up1oadAbc", sourceRef(asset), "rejected calls change nothing");

        assertEquals(204, hook(org, event, sign(HOOK, now, event)));
        assertEquals(204, hook(org, event, sign(HOOK, now, event)), "a redelivered event is harmless");
        assertEquals("mux:Up1oadAsset", sourceRef(asset), "the upload's row is linked, not duplicated");
        assertEquals(1, libraryRows(org).stream().filter(m -> m.get("source_ref").equals("mux:Up1oadAsset")).count());
        assertEquals("Launch trailer", libraryRows(org).stream().filter(m -> m.get("source_ref").equals("mux:Up1oadAsset")).findFirst().orElseThrow().get("title"));
        String status = TenantScope.bypass(() -> db.sql("select status from asset where id = :id").param("id", asset).query(String.class).single());
        assertNotEquals("DRAFT", status, "the ready event queued packaging (it fails here: tests run without ffmpeg)");
    }

    String sourceRef(UUID asset) {
        return TenantScope.bypass(() -> db.sql("select source_ref from asset where id = :id").param("id", asset).query(String.class).single());
    }

    int hook(UUID org, byte[] body, String signature) throws Exception {
        var req = post("/hooks/mux/" + org).contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) req = req.header("Mux-Signature", signature);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    static String sign(String secret, long t, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
