package com.faststudio;

import com.faststudio.v1_0.core.tenant.TenantScope;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real-data pilot profile end to end: no demo content, first admin from configuration, forced password change
 * enforced by the API, org creation with a temporary owner password, and single-use reset links.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pilot")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PilotModeTest {
    static final String ADMIN = "admin@pilot.test", ADMIN_PW = "Initial-Bootstrap-Pass-1", NEW_PW = "My-Own-Strong-Pass-2";
    static final MockHttpSession admin = new MockHttpSession();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-pilot").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("faststudio.admin.email", () -> ADMIN);
        r.add("faststudio.admin.password", () -> ADMIN_PW);
        r.add("faststudio.media-secret", () -> "a-stable-random-secret-of-at-least-32-chars");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;

    MvcResult login(MockHttpSession s, String email, String pw) throws Exception {
        return mvc.perform(post("/api/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, pw))).andReturn();
    }

    MvcResult postJson(MockHttpSession s, String path, String body) throws Exception {
        return mvc.perform(post(path).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    @Order(1)
    void noDemoContentOrAccounts() throws Exception {
        assertEquals(0, TenantScope.bypass(() -> db.sql("select count(*) from organization where kind = 'CUSTOMER'").query(Integer.class).single()));
        assertEquals(1, db.sql("select count(*) from app_user").query(Integer.class).single(), "only the bootstrapped admin");
        String demo = mvc.perform(get("/api/auth/demo-accounts")).andReturn().getResponse().getContentAsString();
        assertTrue(demo.contains("\"enabled\":false"));
        assertEquals(401, login(new MockHttpSession(), "owner@aurora.test", "demo1234").getResponse().getStatus());
    }

    @Test
    @Order(2)
    void firstAdminMustChangePasswordBeforeAnythingElse() throws Exception {
        assertEquals(200, login(admin, ADMIN, ADMIN_PW).getResponse().getStatus());
        assertTrue(mvc.perform(get("/api/auth/me").session(admin)).andReturn().getResponse().getContentAsString().contains("\"mustChangePassword\":true"));
        MvcResult blocked = mvc.perform(get("/api/platform/orgs").session(admin)).andReturn();
        assertEquals(403, blocked.getResponse().getStatus());
        assertTrue(blocked.getResponse().getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"));
        assertEquals(422, postJson(admin, "/api/auth/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(ADMIN_PW)).getResponse().getStatus());
        assertEquals(422, postJson(admin, "/api/auth/password", "{\"currentPassword\":\"wrong-password-x\",\"newPassword\":\"%s\"}".formatted(NEW_PW)).getResponse().getStatus());
        assertEquals(200, postJson(admin, "/api/auth/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(ADMIN_PW, NEW_PW)).getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/api/platform/orgs").session(admin)).andReturn().getResponse().getStatus());
        assertEquals(401, login(new MockHttpSession(), ADMIN, ADMIN_PW).getResponse().getStatus(), "old password no longer works");
    }

    @Test
    @Order(3)
    void ownerGetsTemporaryPasswordAndResetLinksAreSingleUse() throws Exception {
        MvcResult org = postJson(admin, "/api/platform/orgs", """
                {"name":"Real Media Co","slug":"realmedia","country":"IN","timezone":"Asia/Kolkata","ownerEmail":"owner@realmedia.test","ownerName":"Owner"}""");
        assertEquals(200, org.getResponse().getStatus());
        String temp = org.getResponse().getContentAsString().replaceAll(".*\"ownerTemporaryPassword\":\"([^\"]+)\".*", "$1");
        MockHttpSession owner = new MockHttpSession();
        assertEquals(200, login(owner, "owner@realmedia.test", temp).getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/api/orgs/realmedia/channels").session(owner)).andReturn().getResponse().getStatus(), "must change temp password first");

        // platform admin issues a reset link; it works exactly once
        MvcResult link = postJson(admin, "/api/platform/password-reset", "{\"email\":\"owner@realmedia.test\"}");
        assertEquals(200, link.getResponse().getStatus());
        String token = link.getResponse().getContentAsString().replaceAll(".*token=([A-Za-z0-9_-]+).*", "$1");
        MockHttpSession anon = new MockHttpSession();
        assertEquals(422, postJson(anon, "/api/auth/reset", "{\"token\":\"%s\",\"newPassword\":\"weak\"}".formatted(token)).getResponse().getStatus());
        assertEquals(200, postJson(anon, "/api/auth/reset", "{\"token\":\"%s\",\"newPassword\":\"Chosen-Strong-Pass-9\"}".formatted(token)).getResponse().getStatus());
        assertEquals(400, postJson(anon, "/api/auth/reset", "{\"token\":\"%s\",\"newPassword\":\"Another-Pass-Try-7\"}".formatted(token)).getResponse().getStatus(), "single use");
        assertEquals(400, postJson(anon, "/api/auth/reset", "{\"token\":\"forged-token\",\"newPassword\":\"Another-Pass-Try-7\"}").getResponse().getStatus());

        MockHttpSession owner2 = new MockHttpSession();
        assertEquals(200, login(owner2, "owner@realmedia.test", "Chosen-Strong-Pass-9").getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/api/orgs/realmedia/channels").session(owner2)).andReturn().getResponse().getStatus(), "reset clears the forced change");
        assertEquals(1, db.sql("select count(*) from password_reset where used_at is not null").query(Integer.class).single());
        assertEquals(0, db.sql("select count(*) from password_reset where token_hash = :t").param("t", token).query(Integer.class).single(), "tokens are stored hashed");
    }

    @Test
    @Order(4)
    void orgAdminCannotResetSomeoneWithAccessElsewhere() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        login(owner, "owner@realmedia.test", "Chosen-Strong-Pass-9");
        // invite a member, who is also given access to a second organization
        MvcResult inv = postJson(owner, "/api/orgs/realmedia/members", "{\"email\":\"editor@realmedia.test\",\"name\":\"Editor\",\"role\":\"CONTENT_MANAGER\"}");
        assertEquals(200, inv.getResponse().getStatus());
        String editorId = inv.getResponse().getContentAsString().replaceAll(".*\"userId\":\"([^\"]+)\".*", "$1");
        assertEquals(200, postJson(owner, "/api/orgs/realmedia/members/" + editorId + "/password-reset", "{}").getResponse().getStatus());
        postJson(admin, "/api/platform/orgs", """
                {"name":"Second Co","slug":"secondco","country":"IN","timezone":"Asia/Kolkata","ownerEmail":"editor@realmedia.test","ownerName":"Editor"}""");
        MvcResult refused = postJson(owner, "/api/orgs/realmedia/members/" + editorId + "/password-reset", "{}");
        assertEquals(403, refused.getResponse().getStatus());
        assertTrue(refused.getResponse().getContentAsString().contains("RESET_ELSEWHERE"));
    }
}
