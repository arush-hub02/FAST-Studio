package com.faststudio.connectors;

import com.faststudio.core.Tokens;
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

/**
 * Connectors belong to the platform administrator; what a company sees of its ad waterfall is switched on per company:
 * nothing by default, names and order with AD_WATERFALL_VIEW (never the platform's tag URLs), its own tags with AD_OWN_TAGS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectorsTest {
    // a public, resolvable host: tag URLs are checked against private networks when saved
    static final String TAG = "https://pubads.g.doubleclick.net/gampad/ads?iu=/platform-account&correlator=[CACHEBUSTER]";
    static final String OWN = "https://pubads.g.doubleclick.net/gampad/ads?iu=/company-account";
    static String platformSource;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-connectors").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;

    UUID aurora() { return db.sql("select id from organization where slug = 'aurora'").query(UUID.class).single(); }

    MvcResult call(MockHttpServletRequestBuilder req, String as, String body) throws Exception {
        UUID user = db.sql("select id from app_user where email = :e").param("e", as).query(UUID.class).single();
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req.header("Authorization", "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token())).andReturn();
    }

    static String vast(String name, String url) {
        return "{\"name\":\"%s\",\"kind\":\"VAST\",\"tagUrl\":\"%s\",\"timeoutMs\":1000,\"enabled\":true}".formatted(name, url);
    }

    @Test @Order(1)
    void onlyThePlatformAdministratorManagesConnectors() throws Exception {
        String base = "/api/platform/orgs/" + aurora();
        assertEquals(403, call(get(base + "/connectors"), "owner@aurora.test", null).getResponse().getStatus(), "a company owner is not the platform");
        MvcResult add = call(post(base + "/ad-sources"), "admin@platform.test", vast("Platform GAM", TAG));
        assertEquals(200, add.getResponse().getStatus(), add.getResponse().getContentAsString());
        platformSource = add.getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String view = call(get(base + "/connectors"), "admin@platform.test", null).getResponse().getContentAsString();
        assertTrue(view.contains("Platform GAM") && view.contains("Direct-sold campaigns"), view);
        assertFalse(view.contains("iu=/platform-account"), "Stored connector URLs are write-only, including for platform administrators");
    }

    @Test @Order(2)
    void aCompanySeesNothingUntilAFeatureIsOn() throws Exception {
        String path = "/api/orgs/" + aurora() + "/ad-sources";
        assertEquals(403, call(get(path), "owner@aurora.test", null).getResponse().getStatus());
        assertEquals(403, call(post(path), "owner@aurora.test", vast("Own", OWN)).getResponse().getStatus());
    }

    @Test @Order(3)
    void viewShowsNamesAndOrderButNeverThePlatformsTagUrls() throws Exception {
        assertEquals(200, call(put("/api/platform/orgs/" + aurora() + "/features"), "admin@platform.test", "{\"features\":[\"AD_WATERFALL_VIEW\",\"NOT_A_FEATURE\"]}")
                .getResponse().getStatus());
        String list = call(get("/api/orgs/" + aurora() + "/ad-sources"), "operator@aurora.test", null).getResponse().getContentAsString();
        assertTrue(list.contains("Platform GAM"), list);
        assertFalse(list.contains("platform-account"), "the platform's ad server account stays hidden");
        assertEquals(403, call(post("/api/orgs/" + aurora() + "/ad-sources"), "owner@aurora.test", vast("Own", OWN)).getResponse().getStatus(), "view only");
        assertTrue(call(get("/api/auth/me"), "owner@aurora.test", null).getResponse().getContentAsString().contains("\"features\":[\"AD_WATERFALL_VIEW\"]"));
    }

    @Test @Order(4)
    void ownTagsAreTheCompanysOnly() throws Exception {
        call(put("/api/platform/orgs/" + aurora() + "/features"), "admin@platform.test", "{\"features\":[\"AD_OWN_TAGS\"]}");
        String path = "/api/orgs/" + aurora() + "/ad-sources";
        assertEquals(403, call(post(path), "operator@aurora.test", vast("Own", OWN)).getResponse().getStatus(), "still needs ADS_EDIT");
        MvcResult own = call(post(path), "ads@aurora.test", vast("Our own tag", OWN));
        assertEquals(200, own.getResponse().getStatus(), own.getResponse().getContentAsString());
        String list = call(get(path), "ads@aurora.test", null).getResponse().getContentAsString();
        assertTrue(list.contains("\"managedBy\":\"TENANT\""), "a company sees its own connector metadata");
        assertFalse(list.contains("iu=/company-account"), "Stored tenant connector URLs are write-only");
        assertEquals(404, call(delete(path + "/" + platformSource), "ads@aurora.test", null).getResponse().getStatus(), "the platform's source is not the company's");
        assertEquals(405, call(post(path + "/order"), "ads@aurora.test", "{\"ids\":[]}").getResponse().getStatus(), "only the platform orders the waterfall");
        assertEquals(422, call(post(path), "ads@aurora.test", "{\"name\":\"x\",\"kind\":\"DIRECT\",\"timeoutMs\":1000,\"enabled\":true}").getResponse().getStatus());
    }
}
