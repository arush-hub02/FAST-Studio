package com.faststudio.v1.services.team;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.services.mail.EmailTemplates;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Platform administration P2 (people across organizations, accounts switched off), P4 (a project's own media types) and
 * P5 (a project's own email wording with fallback to the platform's).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAdminStagesTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-admin-stages").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    static final String ADMIN = "admin@platform.test";
    static final String OWNER = "owner@aurora.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired JsonMapper json;
    @Autowired EmailTemplates templates;

    UUID org(String slug) { return TenantScope.bypass(() -> db.sql("select id from organization where slug = :s").param("s", slug).query(UUID.class).single()); }

    UUID user(String email) { return TenantScope.bypass(() -> db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single()); }

    String token(String email) { return tokens.mint(user(email), null, UUID.randomUUID()).token(); }

    MvcResult call(MockHttpServletRequestBuilder r, String bearer, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", "Bearer " + bearer)).andReturn();
    }

    int status(MockHttpServletRequestBuilder r, String email, String body) throws Exception { return call(r, token(email), body).getResponse().getStatus(); }

    JsonNode ok(MockHttpServletRequestBuilder r, String email, String body) throws Exception {
        MvcResult res = call(r, token(email), body);
        assertTrue(res.getResponse().getStatus() / 100 == 2, res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString());
    }

    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) if (n.path(field).asString().equals(value)) return n;
        return null;
    }

    @Test
    void peopleAcrossOrganizations() throws Exception {
        UUID aurora = org("aurora"), northwind = org("northwind");
        JsonNode page = ok(get("/api/platform/people?q=aurora.test"), ADMIN, null);
        JsonNode owner = find(page.path("people"), "email", OWNER);
        assertNotNull(owner);
        assertEquals("OWNER", find(owner.path("memberships"), "slug", "aurora").path("role").asString());
        assertTrue(page.path("total").asInt() >= page.path("people").size());
        assertEquals(403, status(get("/api/platform/people"), OWNER, null), "platform only");

        // add a new person, then the same again
        String add = "{\"email\":\"newbie@people.test\",\"name\":\"New Bie\",\"orgId\":\"" + northwind + "\",\"role\":\"ANALYST\"}";
        JsonNode added = ok(post("/api/platform/people"), ADMIN, add);
        assertFalse(added.path("temporaryPassword").isNull(), "new account gets a one-time password");
        String id = added.path("person").path("id").asString();
        assertEquals(409, status(post("/api/platform/people"), ADMIN, add));
        assertEquals(422, status(post("/api/platform/people"), ADMIN, add.replace("ANALYST", "PARTNER_VIEWER").replace("newbie", "other")), "partner role in a customer org");

        // an existing account joins a second organization without a new password
        JsonNode second = ok(post("/api/platform/people"), ADMIN, "{\"email\":\"newbie@people.test\",\"name\":\"x\",\"orgId\":\"" + aurora + "\",\"role\":\"CONTENT_MANAGER\"}");
        assertTrue(second.path("temporaryPassword").isNull());
        assertEquals(2, second.path("person").path("memberships").size());

        ok(put("/api/platform/people/" + id + "/memberships/" + aurora), ADMIN, "{\"role\":\"AD_OPS\"}");
        assertEquals("AD_OPS", find(ok(get("/api/platform/people/" + id), ADMIN, null).path("memberships"), "slug", "aurora").path("role").asString());
        assertEquals(1, ok(delete("/api/platform/people/" + id + "/memberships/" + aurora), ADMIN, null).path("memberships").size());

        // the last owner can't be demoted or removed
        assertEquals(409, status(put("/api/platform/people/" + user(OWNER) + "/memberships/" + aurora), ADMIN, "{\"role\":\"ADMIN\"}"));
        assertEquals(409, status(delete("/api/platform/people/" + user(OWNER) + "/memberships/" + aurora), ADMIN, null));
    }

    @Test
    void switchedOffAccountsStopAtOnce() throws Exception {
        String operator = "operator@aurora.test";
        UUID id = user(operator);
        String before = token(operator);
        assertEquals(200, call(get("/api/orgs/aurora/settings"), before, null).getResponse().getStatus());

        assertTrue(ok(put("/api/platform/people/" + id + "/status"), ADMIN, "{\"disabled\":true}").path("disabled").asBoolean());
        assertEquals(401, call(get("/api/orgs/aurora/settings"), before, null).getResponse().getStatus(), "existing token stops");
        ApiException refused = assertThrows(ApiException.class, () -> tokens.mint(id, null, UUID.randomUUID()));
        assertEquals("ACCOUNT_DISABLED", refused.code);

        assertFalse(ok(put("/api/platform/people/" + id + "/status"), ADMIN, "{\"disabled\":false}").path("disabled").asBoolean());
        assertEquals(200, status(get("/api/orgs/aurora/settings"), operator, null), "back on");
        assertEquals(409, status(put("/api/platform/people/" + user(ADMIN) + "/status"), ADMIN, "{\"disabled\":true}"), "not yourself");
    }

    @Test
    void projectsKeepTheirOwnMediaTypes() throws Exception {
        String base = "/api/orgs/aurora/settings/media-types";
        String trailer = "{\"key\":\"TRAILER\",\"label\":\"Trailer\",\"kind\":\"SHORT\",\"enabled\":true}";
        JsonNode view = ok(post(base), OWNER, trailer);
        assertNotNull(find(view.path("types"), "key", "TRAILER"), "usable in the project");
        assertNotNull(find(view.path("ownTypes"), "key", "TRAILER"));
        assertEquals(409, status(post(base), OWNER, trailer.replace("TRAILER", "MOVIE")), "the platform's key");
        assertEquals(422, status(post(base), OWNER, trailer.replace("SHORT", "MOVIE")), "kind never changes");
        assertEquals(403, status(post(base), "analyst@aurora.test", trailer));
        assertNull(find(ok(get("/api/orgs/northwind/settings"), "owner@northwind.test", null).path("types"), "key", "TRAILER"), "other project");
        assertEquals(409, status(post("/api/platform/media-types"), ADMIN, trailer), "platform can't take a project's key");
        assertEquals("Trailer", ok(get("/api/platform/orgs/" + org("aurora") + "/project-media-types"), ADMIN, null).get(0).path("label").asString());

        UUID asset = TenantScope.bypass(() -> db.sql("select id from asset where org_id = :o limit 1").param("o", org("aurora")).query(UUID.class).single());
        Object was = TenantScope.bypass(() -> db.sql("select media_type from asset where id = :a").param("a", asset).query().singleRow().get("media_type"));
        TenantScope.bypass(() -> db.sql("update asset set media_type = 'TRAILER' where id = :a").param("a", asset).update());
        assertEquals(409, status(delete(base + "/TRAILER"), OWNER, null), "in use");
        TenantScope.bypass(() -> db.sql("update asset set media_type = :t where id = :a").param("t", was).param("a", asset).update());
        assertNull(find(ok(delete(base + "/TRAILER"), OWNER, null).path("types"), "key", "TRAILER"));
    }

    @Test
    void projectsWordTheirOwnEmails() throws Exception {
        String base = "/api/orgs/aurora/email-templates";
        JsonNode list = ok(get(base), OWNER, null);
        JsonNode invite = find(list, "key", "invite_new_user");
        assertFalse(invite.path("customized").asBoolean());
        assertNull(find(list, "key", "test_message"), "platform-only emails are not offered");
        String body = invite.path("body").asString();
        String subject = "Join Aurora on FAST Studio";

        assertEquals(422, status(put(base + "/invite_new_user"), OWNER, json.writeValueAsString(Map.of("subject", subject, "body", "No variables"))));
        assertEquals(403, status(put(base + "/invite_new_user"), "analyst@aurora.test", json.writeValueAsString(Map.of("subject", subject, "body", body))));
        assertEquals(404, status(put(base + "/org_owner_welcome"), OWNER, json.writeValueAsString(Map.of("subject", subject, "body", body))));
        assertTrue(find(ok(put(base + "/invite_new_user"), OWNER, json.writeValueAsString(Map.of("subject", subject, "body", body))), "key", "invite_new_user").path("customized").asBoolean());
        assertEquals(subject, ok(post(base + "/invite_new_user/preview"), OWNER, json.writeValueAsString(Map.of("subject", subject, "body", body))).path("subject").asString());

        // sent emails use the project's wording; other projects keep the platform's
        Map<String, String> vars = EmailTemplates.sample("http://localhost");
        UUID aurora = org("aurora"), northwind = org("northwind");
        assertEquals(subject, TenantScope.bypass(() -> templates.render(aurora, "invite_new_user", vars, "http://localhost", false))[0].subject());
        assertNotEquals(subject, TenantScope.bypass(() -> templates.render(northwind, "invite_new_user", vars, "http://localhost", false))[0].subject());
        assertFalse(find(ok(get("/api/orgs/northwind/email-templates"), "owner@northwind.test", null), "key", "invite_new_user").path("customized").asBoolean());

        assertFalse(find(ok(delete(base + "/invite_new_user"), OWNER, null), "key", "invite_new_user").path("customized").asBoolean(), "back to the platform's");
    }
}
