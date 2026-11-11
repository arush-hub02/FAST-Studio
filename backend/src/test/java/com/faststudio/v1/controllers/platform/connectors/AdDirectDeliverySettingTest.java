package com.faststudio.v1.controllers.platform.connectors;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;
import com.faststudio.v1.services.ads.sources.IAdSource;
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

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Each ad server connection plays its ads stored in R2 (default) or straight from the ad server, the latter only with AD_DIRECT_DELIVERY. */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdDirectDeliverySettingTest {
    static final String TAG = "https://pubads.g.doubleclick.net/gampad/ads?iu=/direct-test&correlator=[CACHEBUSTER]";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-ad-delivery").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired AdDecisionServiceV1 decisions;

    UUID aurora() { return db.sql("select id from organization where slug = 'aurora'").query(UUID.class).single(); }

    MvcResult call(MockHttpServletRequestBuilder req, String body) throws Exception {
        UUID user = db.sql("select id from app_user where email = 'admin@platform.test'").query(UUID.class).single();
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req.header("Authorization", "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token())).andReturn();
    }

    static String vast(String name, String delivery) {
        return "{\"name\":\"%s\",\"kind\":\"VAST\",\"tagUrl\":\"%s\",\"timeoutMs\":1000,\"enabled\":true%s}"
                .formatted(name, TAG, delivery == null ? "" : ",\"delivery\":\"" + delivery + "\"");
    }

    void features(String json) throws Exception {
        assertEquals(200, call(put("/api/platform/orgs/" + aurora() + "/features"), "{\"features\":" + json + "}").getResponse().getStatus());
    }

    String sources() throws Exception {
        return call(get("/api/platform/orgs/" + aurora() + "/ad-sources"), null).getResponse().getContentAsString();
    }

    @Test @Order(1)
    void existingConnectionsDefaultToStore() throws Exception {
        MvcResult add = call(post("/api/platform/orgs/" + aurora() + "/ad-sources"), vast("Plain GAM", null));
        assertEquals(200, add.getResponse().getStatus(), add.getResponse().getContentAsString());
        assertTrue(sources().matches("(?s).*\"name\":\"Plain GAM\"[^}]*\"delivery\":\"STORE_R2\".*"), sources());
    }

    @Test @Order(2)
    void savingDirectWithoutFeatureIsRejected() throws Exception {
        MvcResult r = call(post("/api/platform/orgs/" + aurora() + "/ad-sources"), vast("Direct GAM", "DIRECT"));
        assertEquals(403, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("FEATURE_OFF"), r.getResponse().getContentAsString());
    }

    @Test @Order(3)
    void connectorValueDirectIsRejectedWithoutFeature() throws Exception {
        MvcResult r = call(post("/api/platform/orgs/" + aurora() + "/connector-instances/vast"),
                "{\"name\":\"Direct via connector\",\"values\":{\"tag_url\":\"" + TAG + "\",\"timeout_ms\":\"1000\",\"delivery\":\"DIRECT\"}}");
        assertEquals(403, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertTrue(r.getResponse().getContentAsString().contains("FEATURE_OFF"));
    }

    @Test @Order(4)
    void directIsSavedAndReadWhenFeatureIsOn() throws Exception {
        features("[\"AD_DIRECT_DELIVERY\"]");
        MvcResult add = call(post("/api/platform/orgs/" + aurora() + "/ad-sources"), vast("Direct GAM", "DIRECT"));
        assertEquals(200, add.getResponse().getStatus(), add.getResponse().getContentAsString());
        assertTrue(sources().matches("(?s).*\"name\":\"Direct GAM\"[^}]*\"delivery\":\"DIRECT\".*"), sources());
        assertEquals("DIRECT", decisions.effectiveDelivery(aurora(), config("DIRECT")));
    }

    @Test @Order(5)
    void directWithoutFeatureIsReadAsStore() throws Exception {
        features("[]");
        assertEquals("STORE_R2", decisions.effectiveDelivery(aurora(), config("DIRECT")));
    }

    @Test @Order(7)
    void aSavedDirectSourceStaysEditableAfterTheFeatureIsSwitchedOff() throws Exception {
        features("[\"AD_DIRECT_DELIVERY\"]");
        MvcResult add = call(post("/api/platform/orgs/" + aurora() + "/ad-sources"), vast("Kept direct", "DIRECT"));
        String id = add.getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        features("[]");
        MvcResult pause = call(put("/api/platform/orgs/" + aurora() + "/ad-sources/" + id),
                vast("Kept direct", "DIRECT").replace("\"enabled\":true", "\"enabled\":false"));
        assertEquals(200, pause.getResponse().getStatus(), "pausing keeps the saved delivery: " + pause.getResponse().getContentAsString());
        MvcResult other = call(post("/api/platform/orgs/" + aurora() + "/ad-sources"), vast("New direct", "DIRECT"));
        assertEquals(403, other.getResponse().getStatus(), "choosing Direct anew still needs the feature");
    }

    @Autowired com.faststudio.v1.repositories.ads.AdSourceRepository repository;

    @Test @Order(6)
    void directCheckIsSavedAndReadBack() {
        var p = new com.faststudio.v1.services.ads.sources.DirectAdCheck.Profile("FMP4", true, java.util.List.of(720, 360), java.util.List.of(5.0, 5.0, 2.5));
        UUID org = aurora();
        com.faststudio.v1.core.tenant.TenantScope.as(org, () -> { repository.saveDirectCheck(org, "GAM|creative:x", p); return null; });
        com.faststudio.v1.core.tenant.TenantScope.as(org, () -> { repository.saveDirectCheck(org, "GAM|creative:x", p); return null; });   // again: upsert
        var back = com.faststudio.v1.core.tenant.TenantScope.as(org, () -> repository.directCheck(org, "GAM|creative:x")).orElseThrow();
        assertEquals(p, back.profile());
        assertNotNull(back.checkedAt());
        assertTrue(com.faststudio.v1.core.tenant.TenantScope.as(org, () -> repository.directCheck(org, "GAM|creative:none")).isEmpty());
    }

    static IAdSource.Config config(String delivery) {
        return new IAdSource.Config(UUID.randomUUID(), "x", "VAST", TAG, 1000, 1, true, "PLATFORM", delivery);
    }
}
