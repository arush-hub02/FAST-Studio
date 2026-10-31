package com.faststudio;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Personal access tokens (scripts, MCP clients): created by a signed-in person for one organization with a role ceiling
 * and an expiry (or none); used as "Authorization: Bearer fsp_…"; never more than the ceiling or the owner's current
 * role; limited to the organization API; revocable and expiring at once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pilot")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonalTokenTest {
    static final String ADMIN = "admin@pat.test", PW = "Initial-Bootstrap-Pass-1", OWNER = "olga@patco.test", OWNER_PW = "Chosen-Strong-Pass-9";
    static final MockHttpSession admin = new MockHttpSession(), owner = new MockHttpSession();
    static String org, other, readOnly, readOnlyId, full;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-pat").toString();
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

    MvcResult postAs(MockHttpSession s, String path, String body) throws Exception {
        return mvc.perform(post(path).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    int bearer(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> body(MvcResult r) throws Exception { return json.readValue(r.getResponse().getContentAsString(), Map.class); }

    @SuppressWarnings("unchecked")
    Map<String, Object> create(String name, String role, Integer days, int expect) throws Exception {
        MvcResult r = postAs(owner, "/api/auth/personal-tokens", json.writeValueAsString(
                days == null ? Map.of("name", name, "org", "patco", "role", role) : Map.of("name", name, "org", "patco", "role", role, "expiresInDays", days)));
        assertEquals(expect, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return expect == 200 ? body(r) : Map.of();
    }

    String org(String name, String slug, String email) throws Exception {
        MvcResult r = postAs(admin, "/api/platform/orgs", """
                {"name":"%s","slug":"%s","country":"IN","timezone":"Asia/Kolkata","ownerName":"Olga","ownerEmail":"%s","kind":"CUSTOMER"}""".formatted(name, slug, email));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return (String) body(r).get("ownerTemporaryPassword");
    }

    @Test @Order(1)
    void ownerCreatesTokensWithARoleCeilingAndAnExpiry() throws Exception {
        db.sql("update app_user set must_change_password = false where email = :e").param("e", ADMIN).update();
        assertEquals(200, postAs(admin, "/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN, PW)).getResponse().getStatus());
        String temp = org("Pat Co", "patco", OWNER);
        org("Other Co", "otherco", "oscar@otherco.test");
        org = db.sql("select id::text from organization where slug = 'patco'").query(String.class).single();
        other = db.sql("select id::text from organization where slug = 'otherco'").query(String.class).single();
        assertEquals(200, postAs(owner, "/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(OWNER, temp)).getResponse().getStatus());
        assertEquals(200, postAs(owner, "/api/auth/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temp, OWNER_PW)).getResponse().getStatus());

        Map<String, Object> ro = create("Claude MCP (read-only)", "ANALYST", 30, 200);
        readOnly = (String) ro.get("token");
        @SuppressWarnings("unchecked") Map<String, Object> details = (Map<String, Object>) ro.get("details");
        readOnlyId = (String) details.get("id");
        assertTrue(readOnly.startsWith("fsp_") && readOnly.length() > 40);
        assertNotNull(details.get("expiresAt"));
        full = (String) create("Deploy script", "OWNER", null, 200).get("token");

        List<?> listed = json.readValue(mvc.perform(get("/api/auth/personal-tokens").session(owner)).andReturn().getResponse().getContentAsString(), List.class);
        assertEquals(2, listed.size());
        assertFalse(json.writeValueAsString(listed).contains(readOnly), "the token itself is never listed");
        assertTrue(json.writeValueAsString(listed).contains("\"expiresAt\":null"), "no-expiry tokens are allowed");
        assertEquals(0, db.sql("select count(*) from personal_access_token where token_hash = :t").param("t", readOnly).query(Integer.class).single(),
                "only a hash is stored");
    }

    @Test @Order(2)
    void aTokenActsWithinItsRoleAndOrganizationOnly() throws Exception {
        String alert = "/api/orgs/" + org + "/alerts/" + UUID.randomUUID() + "/acknowledge";
        assertEquals(200, bearer(readOnly, get("/api/orgs/" + org + "/channels")), "read-only token can read");
        assertEquals(403, bearer(readOnly, post(alert)), "read-only token can't change anything");
        assertEquals(409, bearer(full, post(alert)), "owner-level token passes the permission check (the alert simply isn't open)");
        assertEquals(200, bearer(readOnly, get("/api/auth/me")));
        assertEquals(404, bearer(full, get("/api/orgs/" + other + "/channels")), "another organization is not reachable");
        assertEquals(403, bearer(full, get("/api/platform/orgs")), "no platform administration");
        assertEquals(403, bearer(full, post("/api/auth/personal-tokens")), "a token can't make more tokens");
        assertEquals(403, bearer(full, post("/api/auth/password")), "or change the password");
        assertEquals(401, bearer("fsp_not-a-real-token", get("/api/orgs/" + org + "/channels")));
    }

    @Test @Order(3)
    void aTokenNeverExceedsWhatItsOwnerMayDoNow() throws Exception {
        db.sql("update membership set role = 'ADMIN' where org_id = cast(:o as uuid) and user_id = (select id from app_user where email = :e)")
                .param("o", org).param("e", OWNER).update();
        create("Too strong", "OWNER", 7, 403);
        String admin = (String) create("Admin helper", "ADMIN", 7, 200).get("token");
        String alert = "/api/orgs/" + org + "/alerts/" + UUID.randomUUID() + "/acknowledge";
        assertEquals(409, bearer(admin, post(alert)));
        db.sql("update membership set role = 'ANALYST' where org_id = cast(:o as uuid) and user_id = (select id from app_user where email = :e)")
                .param("o", org).param("e", OWNER).update();
        assertEquals(403, bearer(admin, post(alert)), "after a demotion the token is demoted too");
        assertEquals(403, bearer(full, post(alert)));
        db.sql("update membership set role = 'OWNER' where org_id = cast(:o as uuid) and user_id = (select id from app_user where email = :e)")
                .param("o", org).param("e", OWNER).update();
    }

    @Test @Order(4)
    void revokedAndExpiredTokensStopAtOnce() throws Exception {
        assertEquals(204, mvc.perform(delete("/api/auth/personal-tokens/" + readOnlyId).session(owner).with(csrf())).andReturn().getResponse().getStatus());
        assertEquals(401, bearer(readOnly, get("/api/orgs/" + org + "/channels")));
        db.sql("update personal_access_token set expires_at = now() - interval '1 minute' where name = 'Deploy script'").update();
        assertEquals(401, bearer(full, get("/api/orgs/" + org + "/channels")));
        assertTrue(com.faststudio.v1.core.tenant.TenantScope.bypass(() -> db.sql("select count(*) from audit_event where action like 'token.personal.%'")
                .query(Integer.class).single()) >= 3, "created and revoked are audited");
    }

    @Test @Order(5)
    void badTokensAreLoggedButNeverLockOutValidOnes() throws Exception {
        for (int i = 0; i < 25; i++) assertEquals(401, bearer("fsp_guess-" + i, get("/api/orgs/" + org + "/channels")));
        assertTrue(db.sql("select count(*) from auth_attempt where bucket like 'token-ip:%'").query(Integer.class).single() >= 25, "logged per address");
        String fresh = (String) create("Docs: still works", "ANALYST", 1, 200).get("token");
        assertEquals(200, bearer(fresh, get("/api/orgs/" + org + "/channels")), "a valid token from the same address still works");
    }

    String createAs(MockHttpSession s, String org, String role, int expect) throws Exception {
        MvcResult r = postAs(s, "/api/auth/personal-tokens", json.writeValueAsString(Map.of("name", "t-" + role, "org", org, "role", role, "expiresInDays", 90)));
        assertEquals(expect, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return expect == 200 ? (String) body(r).get("token") : null;
    }

    @Test @Order(6)
    void platformAdministratorsCanMakePlatformTokensAndNobodyElse() throws Exception {
        assertNull(createAs(owner, "platform", "PLATFORM_ADMIN", 403), "a customer owner is not a platform administrator");
        String platform = createAs(admin, "platform", "PLATFORM_ADMIN", 200);
        assertEquals(200, bearer(platform, get("/api/platform/orgs")));
        assertEquals(403, bearer(platform, get("/api/orgs/" + org + "/channels")), "platform tokens stay in platform administration");
        assertEquals(403, bearer(platform, post("/api/auth/personal-tokens")));
        db.sql("update app_user set platform_admin = false where email = :e").param("e", ADMIN).update();
        assertEquals(401, bearer(platform, get("/api/platform/orgs")), "stops when its owner stops being a platform administrator");
        db.sql("update app_user set platform_admin = true where email = :e").param("e", ADMIN).update();
    }

    @Test @Order(7)
    void partnerMembersMakeTokensForThePartnerApiWithPartnerRoles() throws Exception {
        MvcResult r = postAs(admin, "/api/platform/orgs", """
                {"name":"View Box","slug":"viewbox","country":"IN","timezone":"Asia/Kolkata","ownerName":"Pia","ownerEmail":"pia@viewbox.test","kind":"PARTNER"}""");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        String temp = (String) body(r).get("ownerTemporaryPassword");
        MockHttpSession pia = new MockHttpSession();
        assertEquals(200, postAs(pia, "/api/auth/login", "{\"email\":\"pia@viewbox.test\",\"password\":\"%s\"}".formatted(temp)).getResponse().getStatus());
        assertEquals(200, postAs(pia, "/api/auth/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"Partner-Strong-Pass-5\"}".formatted(temp)).getResponse().getStatus());

        assertNull(createAs(pia, "viewbox", "OWNER", 403), "customer roles do not apply to a partner organization");
        String viewer = createAs(pia, "viewbox", "PARTNER_VIEWER", 200), padmin = createAs(pia, "viewbox", "PARTNER_ADMIN", 200);
        assertEquals(200, bearer(viewer, get("/api/partners/viewbox/links")));
        String newClient = "{\"name\":\"Feed reader\"}";
        assertEquals(403, mvc.perform(post("/api/partners/viewbox/clients").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content(newClient)).andReturn().getResponse().getStatus(), "a viewer token can't manage API clients");
        assertNotEquals(403, mvc.perform(post("/api/partners/viewbox/clients").header("Authorization", "Bearer " + padmin)
                .contentType(MediaType.APPLICATION_JSON).content(newClient)).andReturn().getResponse().getStatus(),
                "an admin token passes the permission check (the request itself lacks data scopes)");
        assertEquals(403, bearer(padmin, get("/api/platform/orgs")));
        assertEquals(404, bearer(padmin, get("/api/partners/other/links")));
    }
}
