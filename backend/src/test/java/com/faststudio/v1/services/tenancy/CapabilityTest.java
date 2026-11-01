package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.tenant.TenantScope;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Platform-administration Stage 1: the features and permissions catalogue (V24). Global entries are the platform's,
 * custom ones a project's own; built-in entries can't be removed or switched off; the keys that apply to a person come
 * back in project details, so the frontend can show or hide things by identifier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CapabilityTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-capabilities").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired JsonMapper json;

    UUID org(String slug) { return TenantScope.bypass(() -> db.sql("select id from organization where slug = :s").param("s", slug).query(UUID.class).single()); }

    MvcResult call(MockHttpServletRequestBuilder r, String email, String body) throws Exception {
        UUID user = TenantScope.bypass(() -> db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single());
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token())).andReturn();
    }

    /** The access part of project details: what the frontend reads after sign-in. */
    JsonNode access(String slug, String email) throws Exception {
        return json.readTree(call(get("/api/orgs/" + org(slug) + "/project-details"), email, null).getResponse().getContentAsString()).path("access");
    }

    static List<String> keys(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asString()));
        return out;
    }

    @Test @Order(1)
    void platformKeepsTheGlobalCatalogueAndBuiltInsStayEnforced() throws Exception {
        String list = call(get("/api/platform/capabilities"), "admin@platform.test", null).getResponse().getContentAsString();
        assertTrue(list.contains("\"key\":\"LIBRARY_SYNC\"") && list.contains("\"key\":\"SCHEDULE_PUBLISH\""), "built-ins are seeded: " + list);
        assertEquals(403, call(get("/api/platform/capabilities"), "owner@aurora.test", null).getResponse().getStatus(), "platform only");

        String feature = "{\"key\":\"CLIPS\",\"kind\":\"FEATURE\",\"name\":\"Clips\",\"category\":\"Media\"}";
        assertEquals(201, call(post("/api/platform/capabilities"), "admin@platform.test", feature).getResponse().getStatus());
        assertEquals(409, call(post("/api/platform/capabilities"), "admin@platform.test", feature).getResponse().getStatus(), "keys are unique");
        assertEquals(201, call(post("/api/platform/capabilities"), "admin@platform.test",
                "{\"key\":\"CLIPS_EXPORT\",\"kind\":\"PERMISSION\",\"name\":\"Export clips\"}").getResponse().getStatus());
        assertEquals(422, call(post("/api/platform/capabilities"), "admin@platform.test",
                "{\"key\":\"bad key\",\"kind\":\"FEATURE\",\"name\":\"x\"}").getResponse().getStatus(), "identifier format");

        assertEquals(409, call(delete("/api/platform/capabilities/LIBRARY_SYNC"), "admin@platform.test", null).getResponse().getStatus());
        assertEquals(409, call(put("/api/platform/capabilities/ASSETS_EDIT"), "admin@platform.test",
                "{\"name\":\"Edit videos\",\"enabled\":false}").getResponse().getStatus(), "built-ins can't be switched off");
        assertEquals(200, call(put("/api/platform/capabilities/ASSETS_EDIT"), "admin@platform.test",
                "{\"name\":\"Edit media\",\"description\":\"Renamed\"}").getResponse().getStatus(), "but can be renamed");
    }

    @Test @Order(2)
    void aGlobalFeatureIsOnOnlyWhereThePlatformSwitchesItOn() throws Exception {
        assertFalse(keys(access("aurora", "owner@aurora.test").path("features")).contains("CLIPS"));
        MvcResult on = call(put("/api/platform/orgs/" + org("aurora") + "/features"), "admin@platform.test", "{\"features\":[\"CLIPS\"]}");
        assertEquals(200, on.getResponse().getStatus(), on.getResponse().getContentAsString());
        assertTrue(keys(access("aurora", "owner@aurora.test").path("features")).contains("CLIPS"));
        assertFalse(keys(access("northwind", "owner@northwind.test").path("features")).contains("CLIPS"), "only where switched on");

        List<String> owner = keys(access("aurora", "owner@aurora.test").path("permissions"));
        assertTrue(owner.contains("CLIPS_EXPORT") && owner.contains("ASSETS_EDIT"), "owners get custom permissions: " + owner);
        List<String> analyst = keys(access("aurora", "analyst@aurora.test").path("permissions"));
        assertEquals(List.of("VIEW"), analyst, "other built-in roles don't, until a custom role grants them");

        call(put("/api/platform/capabilities/CLIPS"), "admin@platform.test", "{\"name\":\"Clips\",\"enabled\":false}");
        assertFalse(keys(access("aurora", "owner@aurora.test").path("features")).contains("CLIPS"), "switched off globally");
    }

    @Test @Order(3)
    void aProjectKeepsItsOwnCustomEntries() throws Exception {
        String path = "/api/orgs/" + org("aurora") + "/capabilities";
        assertEquals(403, call(post(path), "analyst@aurora.test", "{\"key\":\"AURORA_BETA\",\"kind\":\"FEATURE\",\"name\":\"Beta\"}")
                .getResponse().getStatus(), "needs TEAM_MANAGE");
        assertEquals(201, call(post(path), "owner@aurora.test", "{\"key\":\"AURORA_BETA\",\"kind\":\"FEATURE\",\"name\":\"Beta\"}").getResponse().getStatus());
        assertEquals(201, call(post(path), "owner@aurora.test", "{\"key\":\"AURORA_APPROVE\",\"kind\":\"PERMISSION\",\"name\":\"Approve\"}").getResponse().getStatus());
        assertEquals(409, call(post(path), "owner@aurora.test", "{\"key\":\"CLIPS\",\"kind\":\"FEATURE\",\"name\":\"Mine\"}").getResponse().getStatus(),
                "can't take a global identifier");

        JsonNode view = json.readTree(call(get(path), "owner@aurora.test", null).getResponse().getContentAsString());
        assertTrue(keys(view.path("features")).contains("AURORA_BETA"));
        assertTrue(keys(view.path("permissions")).contains("AURORA_APPROVE"));
        assertTrue(keys(access("aurora", "owner@aurora.test").path("features")).contains("AURORA_BETA"), "project details carries it too");

        String other = "/api/orgs/" + org("northwind") + "/capabilities";
        String theirs = call(get(other), "owner@northwind.test", null).getResponse().getContentAsString();
        assertFalse(theirs.contains("AURORA_BETA"), "another project never sees it");
        assertEquals(404, call(put(other + "/AURORA_BETA"), "owner@northwind.test", "{\"name\":\"Taken\"}").getResponse().getStatus());
        assertEquals(404, call(delete(other + "/AURORA_BETA"), "owner@northwind.test", null).getResponse().getStatus());
        assertEquals(0, TenantScope.as(org("northwind"), () -> db.sql("select count(*) from capability where key = 'AURORA_BETA'")
                .query(Integer.class).single()), "row-level security hides it");
        assertEquals(404, call(put(path + "/ASSETS_EDIT"), "owner@aurora.test", "{\"name\":\"Mine\"}").getResponse().getStatus(),
                "a project can't change the global catalogue");

        assertEquals(200, call(put(path + "/AURORA_BETA"), "owner@aurora.test", "{\"name\":\"Beta\",\"enabled\":false}").getResponse().getStatus());
        assertFalse(keys(access("aurora", "owner@aurora.test").path("features")).contains("AURORA_BETA"), "switched off");
        assertEquals(204, call(delete(path + "/AURORA_BETA"), "owner@aurora.test", null).getResponse().getStatus());
    }
}
