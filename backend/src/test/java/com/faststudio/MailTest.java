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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Email end to end through the real nodemailer mailer (no mail server configured, so it writes .eml files):
 * template rules, customised templates, the outbox, and one-time secrets hidden from the log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pilot")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MailTest {
    static final String ADMIN = "admin@mail.test", ADMIN_PW = "Initial-Bootstrap-Pass-1";
    static final MockHttpSession admin = new MockHttpSession();
    static Path dir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        dir = Files.createTempDirectory("fast-studio-mail");
        r.add("faststudio.data-dir", dir::toString);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("faststudio.admin.email", () -> ADMIN);
        r.add("faststudio.admin.password", () -> ADMIN_PW);
        r.add("faststudio.media-secret", () -> "a-stable-random-secret-of-at-least-32-chars");
        r.add("faststudio.mail.smtp-url", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;

    MvcResult call(String method, String path, String body) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), path).session(admin).with(csrf());
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn();
    }

    record Row(String status, String subject, String bodyText, String bodyHtml, String pendingText, String detail) {}

    /** Waits for the background worker to finish the newest email to this address. */
    Row awaitEmail(String to) throws Exception {
        for (int i = 0; i < 150; i++) {
            Row r = db.sql("select status, subject, body_text, body_html, pending_text, detail from email_message where to_email = :t order by created_at desc limit 1")
                    .param("t", to).query(Row.class).optional().orElse(null);
            if (r != null && !r.status().equals("QUEUED")) return r;
            Thread.sleep(200);
        }
        return fail("email to " + to + " was not processed");
    }

    @Test @Order(1)
    void adminSignsIn() throws Exception {
        db.sql("update app_user set must_change_password = false where email = :e").param("e", ADMIN).update();
        assertEquals(200, mvc.perform(post("/api/auth/login").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN, ADMIN_PW))).andReturn().getResponse().getStatus());
    }

    @Test @Order(2)
    void templatesAreValidatedBeforeSaving() throws Exception {
        String list = call("GET", "/api/platform/email/templates", null).getResponse().getContentAsString();
        for (String key : new String[]{"invite_new_user", "invite_existing_user", "org_owner_welcome", "password_reset", "password_changed", "alert_raised", "test_message", "footer"})
            assertTrue(list.contains("\"" + key + "\""), key);
        MvcResult missing = call("PUT", "/api/platform/email/templates/password_reset", "{\"subject\":\"Reset\",\"body\":\"Hello {{name}}\"}");
        assertEquals(422, missing.getResponse().getStatus());
        assertTrue(missing.getResponse().getContentAsString().contains("{{resetLink}}"));
        assertEquals(422, call("PUT", "/api/platform/email/templates/password_reset", "{\"subject\":\"Reset\",\"body\":\"{{resetLink}} {{salary}}\"}").getResponse().getStatus());
        String preview = call("POST", "/api/platform/email/templates/test_message/preview", "{\"subject\":\"Hi\",\"body\":\"<script>x</script> {{name}}\"}")
                .getResponse().getContentAsString();
        String html = preview.replaceAll("(?s).*\"html\":\"(.*?)\",\"problems\".*", "$1");
        assertTrue(html.contains("&lt;script&gt;") && !html.contains("<script>x"), "template text is escaped in the HTML, never raw");
        assertEquals(200, call("PUT", "/api/platform/email/templates/password_reset",
                "{\"subject\":\"Custom reset for {{name}}\",\"body\":\"Hi {{name}}, pick a new password:\\n\\n{{resetLink}}\"}").getResponse().getStatus());
    }

    @Test @Order(3)
    void newOwnerGetsAnEmailAndTheLogHidesThePassword() throws Exception {
        MvcResult r = call("POST", "/api/platform/orgs", """
                {"name":"Lotus Studios","slug":"lotus","country":"IN","timezone":"Asia/Kolkata","ownerName":"Arjun Mehta","ownerEmail":"arjun@lotus.example"}""");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        String temp = r.getResponse().getContentAsString().replaceAll(".*\"ownerTemporaryPassword\":\"([^\"]+)\".*", "$1");
        assertTrue(r.getResponse().getContentAsString().contains("saved to the outbox"));

        Row row = awaitEmail("arjun@lotus.example");
        assertEquals("SAVED", row.status(), row.detail());
        assertEquals("Your FAST Studio workspace for Lotus Studios is ready", row.subject());
        assertFalse(row.bodyText().contains(temp) || row.bodyHtml().contains(temp), "the log never shows the one-time password");
        assertTrue(row.bodyText().contains("●●●●●●"));
        assertNull(row.pendingText(), "the real copy is deleted once delivered");
        String eml = Files.readString(Path.of(row.detail())).replace("=\r\n", "");
        assertTrue(eml.contains(temp), "the delivered email contains the password");
        assertTrue(eml.contains("To: arjun@lotus.example"));
    }

    @Test @Order(4)
    void customisedTemplateIsUsedAndResetLinkHidden() throws Exception {
        MvcResult r = call("POST", "/api/platform/password-reset", "{\"email\":\"arjun@lotus.example\"}");
        assertEquals(200, r.getResponse().getStatus());
        String token = r.getResponse().getContentAsString().replaceAll(".*token=([^\"]+)\".*", "$1");
        Row row = awaitEmail("arjun@lotus.example");
        assertEquals("Custom reset for Arjun Mehta", row.subject());
        assertFalse(row.bodyHtml().contains(token));
        assertTrue(Files.readString(Path.of(row.detail())).replace("=\r\n", "").contains(token));

        assertEquals(200, call("DELETE", "/api/platform/email/templates/password_reset", null).getResponse().getStatus());
        assertFalse(call("GET", "/api/platform/email/templates", null).getResponse().getContentAsString().contains("Custom reset"));
    }

    @Test @Order(5)
    void everyoneCanAskForAResetLink() throws Exception {
        assertTrue(mvc.perform(get("/api/auth/options")).andReturn().getResponse().getContentAsString().contains("\"passwordResetByEmail\":false"));
        long before = db.sql("select count(*) from email_message").query(Long.class).single();
        for (String who : new String[]{"arjun@lotus.example", ADMIN, "nobody@nowhere.example"}) {
            MvcResult r = mvc.perform(post("/api/auth/forgot").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"%s\"}".formatted(who))).andReturn();
            assertEquals(200, r.getResponse().getStatus());
            assertTrue(r.getResponse().getContentAsString().contains("If an account exists") && r.getResponse().getContentAsString().contains("outbox"),
                    "same answer for every address, and it says the email waits in the outbox");
        }
        assertEquals(before + 2, db.sql("select count(*) from email_message").query(Long.class).single(), "an owner and a platform admin got one; the unknown address none");
        Row row = awaitEmail(ADMIN);
        assertEquals("SAVED", row.status());
        String link = Files.readString(Path.of(row.detail())).replace("=\r\n", "").replace("=3D", "=")
                .replaceAll("(?s).*reset-password\\?token=([A-Za-z0-9_-]+).*", "$1");
        assertEquals(200, mvc.perform(post("/api/auth/reset").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"Recovered-Strong-Pass-3\"}".formatted(link))).andReturn().getResponse().getStatus(),
                "the link in the email works");
        for (int i = 0; i < 3; i++) mvc.perform(post("/api/auth/forgot").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"arjun@lotus.example\"}"));
        assertEquals(3, db.sql("select count(*) from password_reset r join app_user u on u.id = r.user_id where u.email = 'arjun@lotus.example' and r.created_by is null")
                .query(Integer.class).single(), "at most 3 self-service links per hour");
    }
}
