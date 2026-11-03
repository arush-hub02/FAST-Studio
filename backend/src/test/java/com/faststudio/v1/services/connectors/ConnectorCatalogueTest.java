package com.faststudio.v1.services.connectors;

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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Connector marketplace (V26, docs/PLATFORM_ADMIN_PLAN.md P3): connector fields are rows; values are sealed when
 * encrypted and never returned; tenants see published connectors with external fields only and connect a connector only
 * when its rule allows; one project never sees another's connections.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectorCatalogueTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-marketplace").toString();
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
    @Autowired ConnectorCatalogueServiceV1 service;
    @Autowired com.faststudio.v1.util.cloudflare.EdgeClient edge;
    @Autowired com.faststudio.v1.services.ads.AdDecisionServiceV1 decisions;

    UUID org(String slug) { return TenantScope.bypass(() -> db.sql("select id from organization where slug = :s").param("s", slug).query(UUID.class).single()); }

    UUID user(String email) { return TenantScope.bypass(() -> db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single()); }

    MvcResult call(MockHttpServletRequestBuilder r, String email, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", "Bearer " + tokens.mint(user(email), null, UUID.randomUUID()).token())).andReturn();
    }

    int status(MockHttpServletRequestBuilder r, String email, String body) throws Exception { return call(r, email, body).getResponse().getStatus(); }

    JsonNode ok(MockHttpServletRequestBuilder r, String email, String body) throws Exception {
        MvcResult res = call(r, email, body);
        assertEquals(200, res.getResponse().getStatus(), res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString());
    }

    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) if (n.path(field).asString().equals(value)) return n;
        return null;
    }

    static JsonNode state(JsonNode marketplace, String key) {
        for (JsonNode s : marketplace.path("connectors")) if (s.path("connector").path("key").asString().equals(key)) return s;
        return null;
    }

    String aurora() { return "/api/orgs/" + org("aurora") + "/connectors"; }

    @Test @Order(1)
    void fieldsAreRowsTheAdminManages() throws Exception {
        JsonNode c = ok(get("/api/platform/connector-catalogue"), ADMIN, null);
        assertNotNull(find(c.path("categories"), "key", "ADS"));
        JsonNode mux = find(c.path("connectors"), "key", "mux");
        JsonNode secret = find(mux.path("properties"), "key", "token_secret");
        assertTrue(secret.path("encrypted").asBoolean() && secret.path("required").asBoolean(), "seeded Mux secret field");
        assertEquals(403, status(get("/api/platform/connector-catalogue"), OWNER, null), "platform only");

        assertEquals(201, status(post("/api/platform/connector-categories"), ADMIN, "{\"key\":\"ANALYTICS\",\"name\":\"Analytics\"}"));
        assertEquals(409, status(post("/api/platform/connector-categories"), ADMIN, "{\"key\":\"ANALYTICS\",\"name\":\"Again\"}"));
        assertEquals(409, status(delete("/api/platform/connector-categories/MEDIA"), ADMIN, null), "in use");
        assertEquals(200, status(delete("/api/platform/connector-categories/ANALYTICS"), ADMIN, null));

        String region = "{\"label\":\"Region\",\"valueType\":\"SELECT\",\"options\":[\"us\",\"eu\"],\"required\":false,\"encrypted\":false,\"scope\":\"EXTERNAL\"}";
        JsonNode saved = ok(put("/api/platform/connector-definitions/vast/properties/region"), ADMIN, region);
        assertNotNull(find(saved.path("properties"), "key", "region"));
        assertEquals(422, status(put("/api/platform/connector-definitions/vast/properties/bad"), ADMIN,
                "{\"label\":\"Bad\",\"valueType\":\"SELECT\",\"required\":false,\"encrypted\":false,\"scope\":\"EXTERNAL\"}"), "a list needs choices");
        assertEquals(404, status(put("/api/platform/connector-definitions/nope/properties/zone"), ADMIN, region));
    }

    @Test @Order(2)
    void encryptedValuesAreSealedAndNeverReturned() throws Exception {
        UUID aurora = org("aurora");
        String base = "/api/platform/orgs/" + aurora + "/connector-instances/mux";
        JsonNode s = ok(post(base), ADMIN, "{\"values\":{\"token_id\":\"id-1\"}}");
        JsonNode i = s.path("instances").get(0);
        assertFalse(i.path("connected").asBoolean(), "token_secret still missing");
        assertTrue(i.path("missing").toString().contains("token_secret"));

        s = ok(put(base + "/" + i.path("id").asString()), ADMIN, "{\"values\":{\"token_secret\":\"top-secret\"}}");
        i = s.path("instances").get(0);
        assertTrue(i.path("connected").asBoolean());
        assertTrue(i.path("values").path("token_secret").path("configured").asBoolean());
        assertTrue(i.path("values").path("token_secret").path("value").isNull(), "secret never returned");
        assertFalse(s.toString().contains("top-secret"));
        String stored = TenantScope.bypass(() -> db.sql("select value from connector_value where property_key = 'token_secret' and org_id = :o")
                .param("o", aurora).query(String.class).single());
        assertNotEquals("top-secret", stored, "sealed at rest");

        // a blank secret keeps the stored one; adapters read decrypted values by key
        ok(put(base + "/" + i.path("id").asString()), ADMIN, "{\"values\":{\"token_secret\":\"\"}}");
        assertEquals("top-secret", service.values(aurora, "mux").get(0).get("token_secret"));
        assertEquals("id-1", service.values(aurora, "mux").get(0).get("token_id"));
        assertTrue(service.values(org("northwind"), "mux").isEmpty(), "other project has none");
    }

    @Test @Order(3)
    void tenantsFollowTheConnectorRule() throws Exception {
        JsonNode m = ok(get(aurora()), OWNER, null);
        JsonNode mux = state(m, "mux");
        assertNotNull(mux);
        assertFalse(mux.path("canConnect").asBoolean(), "Mux: platform connects it");
        assertNull(find(mux.path("connector").path("properties"), "key", "token_secret"), "internal fields hidden");
        assertEquals(403, status(post(aurora() + "/mux"), OWNER, "{\"values\":{\"environment\":\"prod\"}}"));
        assertNull(state(m, "r2"), "platform-level connector not in a project");

        // a project's own ad servers follow the Ads page rule: ADS_EDIT and the AD_OWN_TAGS feature (P3d)
        UUID aurora = org("aurora");
        TenantScope.bypass(() -> db.sql("update organization set features = replace(features, 'AD_OWN_TAGS', '') where id = :o").param("o", aurora).update());
        assertFalse(state(ok(get(aurora()), OWNER, null), "vast").path("canConnect").asBoolean(), "own ad servers switched off");
        assertEquals(403, status(post(aurora() + "/vast"), OWNER, "{\"values\":{\"tag_url\":\"https://ads.test/vast\"}}"));
        TenantScope.bypass(() -> db.sql("update organization set features = trim(both ',' from features || ',AD_OWN_TAGS') where id = :o").param("o", aurora).update());
        JsonNode vast = state(ok(get(aurora()), OWNER, null), "vast");
        assertTrue(vast.path("canConnect").asBoolean());
        assertEquals(422, status(post(aurora() + "/vast"), OWNER, "{\"values\":{\"tag_url\":\"https://ads.test/vast\",\"timeout_ms\":\"soon\"}}"));
        assertEquals(422, status(post(aurora() + "/vast"), OWNER, "{\"values\":{\"tag_url\":\"ftp://ads.test\"}}"));
        assertEquals(422, status(post(aurora() + "/vast"), OWNER, "{\"values\":{\"nope\":\"x\"}}"), "unknown field");
        JsonNode s = ok(post(aurora() + "/vast"), OWNER, "{\"name\":\"Primary\",\"values\":{\"tag_url\":\"https://ads.test/vast\"}}");
        assertTrue(s.path("connected").asBoolean(), "timeout default fills the required field");
        ok(post(aurora() + "/vast"), OWNER, "{\"name\":\"Backup\",\"values\":{\"tag_url\":\"https://ads.test/backup\"}}");
        assertEquals(2, ok(get(aurora()), OWNER, null).path("connectors").findValues("instances").stream()
                .filter(x -> x.toString().contains("Backup")).findFirst().orElseThrow().size(), "ad servers are a list");
        assertEquals(403, status(post(aurora() + "/vast"), "analyst@aurora.test", "{\"values\":{\"tag_url\":\"https://x.test\"}}"), "needs ADS_EDIT");

        // the waterfall is the connections in list order, the project's own after the existing ones
        var waterfall = TenantScope.as(aurora, () -> decisions.waterfall(aurora)).stream().map(c -> c.name()).toList();
        assertEquals(List.of("Primary", "Backup"), waterfall.subList(waterfall.size() - 2, waterfall.size()));

        // the platform's ad servers are read-only for the project
        JsonNode platform = ok(post("/api/platform/orgs/" + aurora + "/connector-instances/vast"), ADMIN, "{\"name\":\"House SSP\",\"values\":{\"tag_url\":\"https://ssp.test/vast\"}}");
        String house = find(platform.path("instances"), "name", "House SSP").path("id").asString();
        assertFalse(find(state(ok(get(aurora()), OWNER, null), "vast").path("instances"), "name", "House SSP").path("editable").asBoolean());
        assertEquals(403, status(put(aurora() + "/vast/" + house), OWNER, "{\"values\":{\"tag_url\":\"https://evil.test\"}}"));
        assertEquals(403, status(delete(aurora() + "/vast/" + house), OWNER, null));
        ok(delete("/api/platform/orgs/" + aurora + "/connector-instances/vast/" + house), ADMIN, null);

        // Google Ad Manager is its own connector on the VAST adapter: its connection joins the waterfall like any VAST tag
        String gamTag = "https://pubads.g.doubleclick.net/gampad/ads?iu=/1234/fast&cb=[CACHEBUSTER]";
        JsonNode gam = ok(post("/api/platform/orgs/" + aurora + "/connector-instances/gam"), ADMIN, "{\"name\":\"GAM\",\"values\":{\"tag_url\":\"" + gamTag + "\"}}");
        assertTrue(find(gam.path("instances"), "name", "GAM").path("connected").asBoolean());
        var last = TenantScope.as(aurora, () -> decisions.waterfall(aurora)).getLast();
        assertEquals(List.of("GAM", "VAST", gamTag, 1000), List.of(last.name(), last.kind(), last.tagUrl(), last.timeoutMs()));
        ok(delete("/api/platform/orgs/" + aurora + "/connector-instances/gam/" + find(gam.path("instances"), "name", "GAM").path("id").asString()), ADMIN, null);

        // a project still on the implicit default (direct-sold only) keeps direct-sold first when its first ad server is added
        UUID nw = org("northwind");
        TenantScope.as(nw, () -> db.sql("delete from connector_instance where org_id = :o and connector_key in ('direct','vast','gam')").param("o", nw).update());
        assertEquals(List.of("Direct-sold campaigns"), TenantScope.as(nw, () -> decisions.waterfall(nw)).stream().map(c -> c.name()).toList());
        ok(post("/api/platform/orgs/" + nw + "/connector-instances/gam"), ADMIN, "{\"name\":\"GAM\",\"values\":{\"tag_url\":\"" + gamTag + "\"}}");
        assertEquals(List.of("Direct-sold campaigns", "GAM"), TenantScope.as(nw, () -> decisions.waterfall(nw)).stream().map(c -> c.name()).toList());
        TenantScope.as(nw, () -> db.sql("delete from connector_instance where org_id = :o and connector_key in ('direct','gam')").param("o", nw).update());

        // upcoming connectors can't be connected; unpublished ones vanish
        String details = "{\"tier\":\"FREE\",\"status\":\"%s\",\"tenantUpdate\":\"ALLOWED\",\"level\":\"PROJECT\",\"multiInstance\":true}";
        ok(put("/api/platform/connector-definitions/vast/details"), ADMIN, details.formatted("UPCOMING"));
        assertEquals(409, status(post(aurora() + "/vast"), OWNER, "{\"values\":{\"tag_url\":\"https://x.test\"}}"));
        ok(put("/api/platform/connector-definitions/vast/details"), ADMIN, details.formatted("UNPUBLISHED"));
        assertNull(state(ok(get(aurora()), OWNER, null), "vast"));
        assertEquals(404, status(post(aurora() + "/vast"), OWNER, "{\"values\":{}}"));
        ok(put("/api/platform/connector-definitions/vast/details"), ADMIN, details.formatted("AVAILABLE"));
    }

    @Test @Order(4)
    void projectsNeverSeeEachOthersConnections() throws Exception {
        JsonNode aurora = state(ok(get(aurora()), OWNER, null), "vast");
        String id = aurora.path("instances").get(0).path("id").asString();
        String northwind = "/api/orgs/" + org("northwind") + "/connectors";
        TenantScope.bypass(() -> db.sql("update organization set features = trim(both ',' from features || ',AD_OWN_TAGS') where slug = 'northwind'").update());
        assertEquals(0, state(ok(get(northwind), "owner@northwind.test", null), "vast").path("instances").size());
        assertEquals(404, status(put(northwind + "/vast/" + id), "owner@northwind.test", "{\"values\":{\"tag_url\":\"https://evil.test\"}}"));
        assertEquals(404, status(delete(northwind + "/vast/" + id), "owner@northwind.test", null));
        assertEquals(404, status(get(aurora()), "owner@northwind.test", null), "not a member: hidden");

        // the platform-level connector holds one set of values
        JsonNode r2 = state(ok(get("/api/platform/connector-instances"), ADMIN, null), "r2");
        assertNotNull(r2);
        assertTrue(r2.path("connector").path("properties").toString().contains("edge_secret"), "platform sees internal fields");

        // the edge client follows the platform's R2 connection, field by field over its start-up settings (P3c)
        ok(post("/api/platform/connector-instances/r2"), ADMIN, "{\"values\":{\"worker_url\":\"https://edge.test\",\"edge_secret\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\",\"public_url\":\"https://media.test/\"}}");
        edge.refresh();
        assertEquals("https://media.test", edge.publicUrl());
        assertTrue(edge.configured(), "secret from the connection");
    }
}
