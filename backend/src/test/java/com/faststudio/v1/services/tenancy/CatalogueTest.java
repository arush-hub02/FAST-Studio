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
 * Platform-administration redesign (V25, docs/PLATFORM_ADMIN_PLAN.md): the global catalogue of modules, feature groups,
 * features (with the permissions they use) and permissions; projects link global features; roles are global or a
 * project's own; the keys that apply to a person come back in project details.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogueTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-catalogue").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    static final String ADMIN = "admin@platform.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired JsonMapper json;

    UUID org(String slug) { return TenantScope.bypass(() -> db.sql("select id from organization where slug = :s").param("s", slug).query(UUID.class).single()); }

    UUID user(String email) { return TenantScope.bypass(() -> db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single()); }

    MvcResult call(MockHttpServletRequestBuilder r, String email, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", "Bearer " + tokens.mint(user(email), null, UUID.randomUUID()).token())).andReturn();
    }

    int status(MockHttpServletRequestBuilder r, String email, String body) throws Exception { return call(r, email, body).getResponse().getStatus(); }

    JsonNode read(MockHttpServletRequestBuilder r, String email) throws Exception {
        MvcResult res = call(r, email, null);
        assertEquals(200, res.getResponse().getStatus(), res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString());
    }

    JsonNode access(String slug, String email) throws Exception { return read(get("/api/orgs/" + org(slug) + "/project-details"), email).path("access"); }

    static List<String> keys(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.isString() ? x.asString() : x.path("key").asString()));
        return out;
    }

    @Test @Order(1)
    void platformKeepsModulesGroupsFeaturesAndPermissions() throws Exception {
        JsonNode c = read(get("/api/platform/catalogue"), ADMIN);
        assertEquals(5, c.path("modules").size(), "seeded modules");
        assertTrue(keys(c.path("groups")).contains("LIBRARY"));
        assertTrue(keys(c.path("permissions")).contains("SCHEDULE_PUBLISH"), "built-in permissions");
        assertEquals(403, status(get("/api/platform/catalogue"), "owner@aurora.test", null), "platform only");

        assertEquals(201, status(post("/api/platform/modules"), ADMIN, "{\"key\":\"STUDIO\",\"name\":\"Studio\"}"));
        assertEquals(201, status(post("/api/platform/feature-groups"), ADMIN, "{\"key\":\"CLIPS_GROUP\",\"moduleKey\":\"STUDIO\",\"name\":\"Clips\"}"));
        assertEquals(422, status(post("/api/platform/feature-groups"), ADMIN, "{\"key\":\"ORPHAN\",\"moduleKey\":\"NOPE\",\"name\":\"x\"}"), "unknown module");
        assertEquals(201, status(post("/api/platform/permissions"), ADMIN, "{\"key\":\"CLIPS_EXPORT\",\"name\":\"Export clips\",\"category\":\"Studio\"}"));
        assertEquals(409, status(post("/api/platform/permissions"), ADMIN, "{\"key\":\"CLIPS_EXPORT\",\"name\":\"Again\"}"), "keys are unique");
        assertEquals(422, status(post("/api/platform/features"), ADMIN,
                "{\"key\":\"CLIPS\",\"name\":\"Clips\",\"groupKey\":\"CLIPS_GROUP\",\"permissions\":[\"NOT_A_PERMISSION\"]}"), "unknown permission");
        assertEquals(201, status(post("/api/platform/features"), ADMIN,
                "{\"key\":\"CLIPS\",\"name\":\"Clips\",\"groupKey\":\"CLIPS_GROUP\",\"permissions\":[\"CLIPS_EXPORT\",\"ASSETS_EDIT\"],\"isDefault\":true}"));
        assertEquals(201, status(post("/api/platform/features"), ADMIN,
                "{\"key\":\"INTERNAL_TOOLS\",\"name\":\"Internal\",\"groupKey\":\"CLIPS_GROUP\",\"permissions\":[],\"hidden\":true}"));

        JsonNode clips = null;
        for (JsonNode f : read(get("/api/platform/catalogue"), ADMIN).path("features")) if (f.path("key").asString().equals("CLIPS")) clips = f;
        assertNotNull(clips);
        assertEquals(List.of("ASSETS_EDIT", "CLIPS_EXPORT"), keys(clips.path("permissions")), "the permissions the feature uses");

        assertEquals(409, status(put("/api/platform/permissions/ASSETS_EDIT"), ADMIN, "{\"key\":\"ASSETS_EDIT\",\"name\":\"Edit\",\"enabled\":false}"), "built-ins stay on");
        assertEquals(409, status(delete("/api/platform/features/LIBRARY_SYNC"), ADMIN, null), "built-ins can't be removed");
        assertEquals(409, status(delete("/api/platform/modules/STUDIO"), ADMIN, null), "a module with groups can't be removed");
        assertEquals(422, status(put("/api/platform/modules/STUDIO"), ADMIN, "{\"key\":\"OTHER\",\"name\":\"x\"}"), "identifiers never change");
    }

    @Test @Order(2)
    void aProjectLinksGlobalFeatures() throws Exception {
        String path = "/api/orgs/" + org("aurora") + "/features";
        JsonNode view = read(get(path), "owner@aurora.test");
        List<String> available = keys(view.path("available"));
        assertTrue(available.contains("CLIPS"));
        assertFalse(available.contains("INTERNAL_TOOLS"), "hidden features aren't offered");
        assertFalse(keys(view.path("features")).contains("CLIPS"), "not linked yet");

        assertEquals(403, status(put(path + "/CLIPS"), "analyst@aurora.test", null), "needs TEAM_MANAGE");
        assertEquals(409, status(put(path + "/LIBRARY_SYNC"), "owner@aurora.test", null), "built-in features are the platform's");
        assertEquals(404, status(put(path + "/INTERNAL_TOOLS"), "owner@aurora.test", null));
        assertEquals(200, status(put(path + "/CLIPS"), "owner@aurora.test", null));
        assertTrue(keys(access("aurora", "owner@aurora.test").path("features")).contains("CLIPS"), "project details carries it");
        assertFalse(keys(access("northwind", "owner@northwind.test").path("features")).contains("CLIPS"), "only where linked");

        assertEquals(200, status(delete(path + "/CLIPS"), "owner@aurora.test", null));
        assertFalse(keys(access("aurora", "owner@aurora.test").path("features")).contains("CLIPS"));
        JsonNode defaults = json.readTree(call(post(path + "/defaults"), "owner@aurora.test", null).getResponse().getContentAsString());
        assertTrue(keys(defaults.path("linked")).contains("CLIPS"), "default features are linked in one go");
    }

    @Test @Order(3)
    void rolesAreGlobalOrAProjectsOwn() throws Exception {
        List<String> owner = keys(access("aurora", "owner@aurora.test").path("permissions"));
        assertTrue(owner.contains("CLIPS_EXPORT"), "owners get custom permissions: " + owner);
        assertEquals(List.of("VIEW"), keys(access("aurora", "analyst@aurora.test").path("permissions")));

        String roles = "/api/orgs/" + org("aurora") + "/roles";
        String role = "{\"key\":\"clip-reviewer\",\"name\":\"Clip reviewer\",\"kind\":\"CUSTOMER\",\"baseRole\":\"ANALYST\",\"permissions\":[\"VIEW\",\"CLIPS_EXPORT\"],\"enabled\":true}";
        assertEquals(403, status(put(roles), "analyst@aurora.test", role), "needs TEAM_MANAGE");
        assertEquals(422, status(put(roles), "owner@aurora.test", role.replace("ANALYST", "OWNER")), "owner stays built-in");
        assertEquals(422, status(put(roles), "owner@aurora.test", role.replace("\"CLIPS_EXPORT\"", "\"SCHEDULE_PUBLISH\"")), "within the ceiling");
        assertEquals(200, status(put(roles), "owner@aurora.test", role));
        assertEquals(409, status(put("/api/orgs/" + org("northwind") + "/roles"), "owner@northwind.test", role), "role keys are unique across projects");

        assertFalse(call(get("/api/orgs/" + org("northwind") + "/roles"), "owner@northwind.test", null).getResponse().getContentAsString().contains("clip-reviewer"),
                "another project never sees it");
        assertEquals(0, TenantScope.as(org("northwind"), () -> db.sql("select count(*) from access_role where key = 'clip-reviewer'").query(Integer.class).single()),
                "row-level security hides it");
        assertFalse(call(get("/api/platform/access-roles"), ADMIN, null).getResponse().getContentAsString().contains("clip-reviewer"),
                "the platform list holds global roles");

        UUID lena = user("analyst@aurora.test");
        MvcResult assign = call(patch("/api/orgs/" + org("aurora") + "/members/" + lena), "owner@aurora.test",
                "{\"role\":\"ANALYST\",\"customRoleKey\":\"clip-reviewer\"}");
        assertEquals(200, assign.getResponse().getStatus(), assign.getResponse().getContentAsString());
        assertEquals(List.of("CLIPS_EXPORT", "VIEW"), keys(access("aurora", "analyst@aurora.test").path("permissions")),
                "the project role grants its custom permission");
        assertEquals(409, status(delete(roles + "/clip-reviewer"), "owner@aurora.test", null), "in use");
    }

    @Test @Order(4)
    void thePlatformManagesAProjectsFeaturesAndRolesFromTheTenantPage() throws Exception {
        String base = "/api/platform/orgs/" + org("northwind");
        assertEquals(403, status(get(base + "/project-features"), "owner@northwind.test", null), "platform only");
        assertTrue(keys(read(get(base + "/project-features"), ADMIN).path("available")).contains("INTERNAL_TOOLS"), "the platform also sees hidden features");
        assertEquals(200, status(put(base + "/project-features/CLIPS"), ADMIN, null));
        assertTrue(keys(access("northwind", "owner@northwind.test").path("features")).contains("CLIPS"));
        assertEquals(200, status(delete(base + "/project-features/CLIPS"), ADMIN, null));
        assertFalse(keys(access("northwind", "owner@northwind.test").path("features")).contains("CLIPS"));

        String role = "{\"key\":\"nw-editor\",\"name\":\"Editor\",\"kind\":\"CUSTOMER\",\"baseRole\":\"CONTENT_MANAGER\",\"permissions\":[\"VIEW\",\"ASSETS_EDIT\"],\"enabled\":true}";
        assertEquals(200, status(put(base + "/roles"), ADMIN, role));
        assertTrue(call(get("/api/orgs/" + org("northwind") + "/roles"), "owner@northwind.test", null).getResponse().getContentAsString().contains("nw-editor"),
                "the project sees the role the platform made for it");
        assertFalse(read(get("/api/platform/orgs/" + org("aurora") + "/roles"), ADMIN).toString().contains("nw-editor"), "only in that project");
        assertEquals(200, status(delete(base + "/roles/nw-editor"), ADMIN, null));

        UUID partner = org("partner-tv");
        assertEquals(404, status(get("/api/platform/orgs/" + partner + "/project-features"), ADMIN, null), "customer projects only");
    }
}
