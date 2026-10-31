package com.faststudio;

import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Production hardening: database-backed sign-in / reset / partner-token limits (per account and per client address),
 * ad-tag URLs encrypted at rest (including the start-up upgrade of old plaintext rows), and browser security headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pilot")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityHardeningTest {
    static final String ADMIN = "admin@hardening.test", PW = "Initial-Bootstrap-Pass-1";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-hardening").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("faststudio.admin.email", () -> ADMIN);
        r.add("faststudio.admin.password", () -> PW);
        r.add("faststudio.media-secret", () -> "a-stable-random-secret-of-at-least-32-chars");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired AdDecisionServiceV1 decisions;

    static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder b, String ip) {
        return b.with(r -> { r.setRemoteAddr(ip); return r; });
    }

    int login(String email, String password, String ip) throws Exception {
        return mvc.perform(from(post("/api/auth/login"), ip).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn().getResponse().getStatus();
    }

    @Test @Order(1)
    void repeatedWrongPasswordsLockTheAccountForAWhileButNotOthers() throws Exception {
        db.sql("update app_user set must_change_password = false where email = :e").param("e", ADMIN).update();
        for (int i = 0; i < 4; i++) assertEquals(401, login(ADMIN, "wrong-" + i, "203.0.113.1"));
        assertEquals(200, login(ADMIN, PW, "203.0.113.1"), "a success clears the account's failures");
        for (int i = 0; i < 5; i++) assertEquals(401, login(ADMIN, "wrong-" + i, "203.0.113.2"));
        assertEquals(429, login(ADMIN, PW, "203.0.113.3"), "after 5 failures even the right password waits, from any address");
        assertEquals(401, login("someone-else@hardening.test", "x", "203.0.113.3"), "other accounts are not affected");
        assertTrue(db.sql("select count(*) from auth_attempt where bucket like 'login-email:%' and bucket like '%@%'").query(Integer.class).single() == 0,
                "emails are stored only as hashes");
    }

    @Test @Order(2)
    void oneAddressTryingManyAccountsIsStopped() throws Exception {
        String ip = "198.51.100.7";
        for (int i = 0; i < 20; i++) assertEquals(401, login("spray" + i + "@hardening.test", "Summer2026!", ip));
        assertEquals(429, login("spray-next@hardening.test", "Summer2026!", ip), "password spraying from one address");
        assertEquals(401, login("spray-next@hardening.test", "Summer2026!", "198.51.100.8"), "other addresses are not affected");
    }

    @Test @Order(3)
    void resetEmailsAreLimitedPerAddress() throws Exception {
        String ip = "192.0.2.50";
        for (int i = 0; i < 10; i++)
            assertEquals(200, mvc.perform(from(post("/api/auth/forgot"), ip).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"user" + i + "@hardening.test\"}")).andReturn().getResponse().getStatus());
        assertEquals(429, mvc.perform(from(post("/api/auth/forgot"), ip).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user-next@hardening.test\"}")).andReturn().getResponse().getStatus());
    }

    int clientToken(String basic, String ip) throws Exception {
        return mvc.perform(from(post("/oauth/token"), ip).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").header("Authorization", "Basic " + basic)).andReturn().getResponse().getStatus();
    }

    @Test @Order(4)
    void partnerTokenGuessingIsLimitedAndBadHeadersAreClientErrors() throws Exception {
        assertEquals(401, clientToken("%%%not-base64%%%", "192.0.2.80"), "malformed Basic header is invalid_client, not a server error");
        String ip = "192.0.2.81";
        String guess = java.util.Base64.getEncoder().encodeToString((UUID.randomUUID() + ":guess").getBytes());
        for (int i = 0; i < 20; i++) assertEquals(401, clientToken(guess, ip));
        assertEquals(429, clientToken(guess, ip));
    }

    @Test @Order(5)
    @SuppressWarnings("unchecked")
    void adTagUrlsAreEncryptedAtRestAndOldPlaintextIsUpgraded() throws Exception {
        db.sql("delete from auth_attempt").update();   // test 1 locked the admin account on purpose
        MockHttpSession admin = new MockHttpSession();
        assertEquals(200, mvc.perform(from(post("/api/auth/login"), "203.0.113.200").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN, PW))).andReturn().getResponse().getStatus());
        MvcResult org = mvc.perform(post("/api/platform/orgs").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Tag Co","slug":"tagco","country":"IN","timezone":"Asia/Kolkata","ownerName":"Tia","ownerEmail":"tia@tagco.test","kind":"CUSTOMER"}"""))
                .andReturn();
        assertEquals(200, org.getResponse().getStatus(), org.getResponse().getContentAsString());
        UUID orgId = db.sql("select id from organization where slug = 'tagco'").query(UUID.class).single();

        String tag = "https://example.com/vast?account=secret-123&cb=[CACHEBUSTING]";
        MvcResult add = mvc.perform(post("/api/platform/orgs/" + orgId + "/ad-sources").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Example SSP", "kind", "VAST", "tagUrl", tag, "timeoutMs", 1000, "enabled", true)))).andReturn();
        assertEquals(200, add.getResponse().getStatus(), add.getResponse().getContentAsString());
        UUID sourceId = UUID.fromString((String) json.readValue(add.getResponse().getContentAsString(), Map.class).get("id"));

        String stored = TenantScope.bypass(() -> db.sql("select tag_url from ad_source where id = :id").param("id", sourceId).query(String.class).single());
        assertFalse(stored.contains("secret-123") || stored.contains("://"), "stored encrypted, not as the URL");
        assertEquals(tag, TenantScope.as(orgId, () -> decisions.waterfall(orgId)).stream().filter(s -> s.id().equals(sourceId)).findFirst().orElseThrow().tagUrl(),
                "the waterfall reads it back decrypted");

        // a row saved before encryption existed is re-sealed by the start-up upgrade and still works before that
        TenantScope.bypass(() -> db.sql("update ad_source set tag_url = :u where id = :id").param("u", tag).param("id", sourceId).update());
        assertEquals(tag, TenantScope.as(orgId, () -> decisions.waterfall(orgId)).stream().filter(s -> s.id().equals(sourceId)).findFirst().orElseThrow().tagUrl());
        var upgrade = Class.forName("com.faststudio.v1.services.ads.AdSourceAdminService").getDeclaredMethod("sealPlaintextTags");
        upgrade.setAccessible(true);
        upgrade.invoke(applicationBean("adSourceAdminService"));
        assertFalse(TenantScope.bypass(() -> db.sql("select tag_url from ad_source where id = :id").param("id", sourceId).query(String.class).single()).contains("://"));
    }

    @Autowired org.springframework.context.ApplicationContext context;

    Object applicationBean(String name) { return context.getBean(name); }

    @Test @Order(6)
    void pagesCarrySecurityHeaders() throws Exception {
        var res = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        String csp = res.getHeader("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("script-src 'self';") && csp.contains("frame-ancestors 'none'") && csp.contains("object-src 'none'"), csp);
        assertTrue(csp.contains("127.0.0.1:8787"), "the delivery edge may serve video: " + csp);
        assertEquals("strict-origin-when-cross-origin", res.getHeader("Referrer-Policy"));
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
        assertNotNull(res.getHeader("Permissions-Policy"));
    }
}
