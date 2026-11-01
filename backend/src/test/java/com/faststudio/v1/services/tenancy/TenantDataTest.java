package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.tenant.TenantScope;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Platform export and permanent deletion of one customer tenant, leaving every other tenant untouched. */
@SpringBootTest
@AutoConfigureMockMvc
class TenantDataTest {
    static final String ADMIN = "admin@platform.test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-tenant-data").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired JsonMapper json;

    <T> T sys(java.util.function.Supplier<T> work) { return TenantScope.bypass(work); }

    long count(String sql, UUID id) { return sys(() -> db.sql(sql).param("id", id).query(Long.class).single()); }

    MvcResult call(MockHttpServletRequestBuilder r, String email, String body) throws Exception {
        UUID user = sys(() -> db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single());
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token())).andReturn();
    }

    @Test
    void exportThenDeleteOneTenantOnly() throws Exception {
        UUID northwind = sys(() -> db.sql("select id from organization where slug = 'northwind'").query(UUID.class).single());
        UUID aurora = sys(() -> db.sql("select id from organization where slug = 'aurora'").query(UUID.class).single());
        UUID tenant = sys(() -> db.sql("select tenant_id from organization where id = :o").param("o", northwind).query(UUID.class).single());
        String name = sys(() -> db.sql("select name from customer_tenant where id = :t").param("t", tenant).query(String.class).single());
        UUID owner = sys(() -> db.sql("select id from app_user where email = 'owner@northwind.test'").query(UUID.class).single());
        long auroraAssets = count("select count(*) from asset where org_id = :id", aurora);
        String base = "/api/platform/tenants/" + tenant;

        // export: the tenant's rows, its members, and no credentials
        MvcResult ex = call(get(base + "/export"), ADMIN, null);
        assertEquals(200, ex.getResponse().getStatus(), ex.getResponse().getContentAsString());
        JsonNode export = json.readTree(ex.getResponse().getContentAsString());
        assertTrue(export.path("tables").has("asset") && export.path("tables").has("channel"), export.path("tables").propertyNames().toString());
        assertTrue(export.path("members").toString().contains("owner@northwind.test"));
        assertFalse(export.toString().contains(aurora.toString()), "nothing of another tenant");
        for (String secret : TenantDataRepositoryColumns.SECRET) assertFalse(export.toString().contains("\"" + secret + "\""), secret);
        assertEquals(403, call(get(base + "/export"), "owner@northwind.test", null).getResponse().getStatus(), "platform only");

        // delete: suspended first, exact name typed
        assertEquals(409, call(post(base + "/delete"), ADMIN, "{\"confirm\":\"" + name + "\"}").getResponse().getStatus());
        assertEquals(200, call(post(base + "/status"), ADMIN, "{\"status\":\"SUSPENDED\"}").getResponse().getStatus());
        assertEquals(400, call(post(base + "/delete"), ADMIN, "{\"confirm\":\"wrong\"}").getResponse().getStatus());
        MvcResult del = call(post(base + "/delete"), ADMIN, "{\"confirm\":\"" + name + "\"}");
        assertEquals(200, del.getResponse().getStatus(), del.getResponse().getContentAsString());

        assertEquals(0, count("select count(*) from organization where tenant_id = :id", tenant));
        assertEquals(0, count("select count(*) from customer_tenant where id = :id", tenant));
        assertEquals(0, count("select count(*) from asset where org_id = :id", northwind));
        assertEquals(0, count("select count(*) from channel where org_id = :id", northwind));
        assertEquals(0, count("select count(*) from membership where user_id = :id", owner));
        String email = sys(() -> db.sql("select email from app_user where id = :id").param("id", owner).query(String.class).single());
        assertTrue(email.endsWith("@deleted.invalid"), "people of only this tenant are anonymized: " + email);
        assertEquals(auroraAssets, count("select count(*) from asset where org_id = :id", aurora), "other tenants untouched");
        assertEquals(200, call(get("/api/orgs/" + aurora + "/assets"), "owner@aurora.test", null).getResponse().getStatus());
    }

    /** Column names the export must never contain. */
    static final class TenantDataRepositoryColumns {
        static final java.util.Set<String> SECRET = com.faststudio.v1.repositories.tenancy.TenantDataRepository.SECRET_COLUMNS;
    }
}
