package com.faststudio;

import org.junit.jupiter.api.Test;
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

/** faststudio.auth.mode=session keeps the previous server-session sign-in for one release; tokens are switched off. */
@SpringBootTest(properties = "faststudio.auth.mode=session")
@AutoConfigureMockMvc
@ActiveProfiles("pilot")
class SessionModeTest {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-session").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("faststudio.admin.email", () -> "admin@session.test");
        r.add("faststudio.admin.password", () -> "Initial-Bootstrap-Pass-1");
        r.add("faststudio.media-secret", () -> "a-stable-random-secret-of-at-least-32-chars");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;

    @Test
    void sessionSignInStillWorks() throws Exception {
        db.sql("update app_user set must_change_password = false where email = 'admin@session.test'").update();
        MockHttpSession s = new MockHttpSession();
        MvcResult login = mvc.perform(post("/api/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@session.test\",\"password\":\"Initial-Bootstrap-Pass-1\"}")).andReturn();
        assertEquals(200, login.getResponse().getStatus());
        assertTrue(login.getResponse().getHeaders("Set-Cookie").stream().noneMatch(c -> c.startsWith("fs_")), "no token cookies");
        assertEquals(200, mvc.perform(get("/api/platform/orgs").session(s)).andReturn().getResponse().getStatus(), "the server session signs requests");
        assertEquals(404, mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@session.test\",\"password\":\"Initial-Bootstrap-Pass-1\"}")).andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getStatus());
        assertEquals(204, mvc.perform(post("/api/auth/logout").session(s).with(csrf())).andReturn().getResponse().getStatus());
    }
}
