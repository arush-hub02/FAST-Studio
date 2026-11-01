package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Per-project limits (V19 properties): API requests per minute, stored media hours, videos converting at once. */
@SpringBootTest
@AutoConfigureMockMvc
class TenantLimitsTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-limits").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("spring.datasource.url", () -> "embedded");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;

    UUID org(String slug) { return db.sql("select id from organization where slug = :s").param("s", slug).query(UUID.class).single(); }

    MvcResult call(MockHttpServletRequestBuilder r, String email) throws Exception {
        UUID user = db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).single();
        return mvc.perform(r.header("Authorization", "Bearer " + tokens.mint(user, null, UUID.randomUUID()).token())).andReturn();
    }

    void limit(UUID org, String key, String value) {
        TenantScope.bypass(() -> db.sql("insert into project_property_value(org_id, property_key, value) values (:o, :k, :v)")
                .param("o", org).param("k", key).param("v", value).update());
    }

    UUID asset(UUID org, String status, long durationMs) {
        return TenantScope.bypass(() -> db.sql("insert into asset(org_id, title, kind, status, duration_ms) values (:o, 'Limit test', 'MOVIE', :s, :d) returning id")
                .param("o", org).param("s", status).param("d", durationMs).query(UUID.class).single());
    }

    @Test
    void apiRequestsPerMinuteArePerProject() throws Exception {
        UUID northwind = org("northwind");
        limit(northwind, TenantLimitsService.API_PER_MINUTE, "3");
        String path = "/api/orgs/" + northwind + "/assets";
        for (int i = 0; i < 3; i++) assertEquals(200, call(get(path), "owner@northwind.test").getResponse().getStatus());
        MvcResult over = call(get(path), "owner@northwind.test");
        assertEquals(429, over.getResponse().getStatus());
        assertTrue(over.getResponse().getContentAsString().contains("RATE_LIMITED"));
        assertEquals(200, call(get("/api/orgs/" + org("aurora") + "/assets"), "owner@aurora.test").getResponse().getStatus(),
                "another project keeps its own allowance");
    }

    @Test
    void conversionsAndStoredHoursAreCapped() throws Exception {
        UUID aurora = org("aurora");
        limit(aurora, TenantLimitsService.PROCESSING, "1");
        limit(aurora, TenantLimitsService.MEDIA_HOURS, "1");
        asset(aurora, "PROCESSING", 7_200_000);                       // converting, and two hours stored
        UUID failed = asset(aurora, "FAILED", 0);
        MvcResult retry = call(post("/api/orgs/" + aurora + "/assets/" + failed + "/retry"), "owner@aurora.test");
        assertEquals(409, retry.getResponse().getStatus());
        assertTrue(retry.getResponse().getContentAsString().contains("PROCESSING_LIMIT"), retry.getResponse().getContentAsString());

        db.sql("update organization set features = 'LIBRARY_SYNC' where id = :o").param("o", aurora).update();
        MvcResult sync = call(post("/api/orgs/" + aurora + "/library/sync"), "owner@aurora.test");
        assertEquals(409, sync.getResponse().getStatus());
        assertTrue(sync.getResponse().getContentAsString().contains("MEDIA_LIMIT"), sync.getResponse().getContentAsString());
    }

    @Test
    void limitsMustBeWholeNumbers() throws Exception {
        // platform API validation of the built-in limit properties
        UUID tenant = TenantScope.bypass(() -> db.sql("select tenant_id from organization where slug = 'aurora'").query(UUID.class).single());
        MvcResult bad = call(put("/api/platform/tenants/" + tenant + "/properties/" + TenantLimitsService.MEDIA_HOURS)
                .contentType("application/json").content("{\"value\":-5}"), "admin@platform.test");
        assertEquals(422, bad.getResponse().getStatus(), bad.getResponse().getContentAsString());
    }
}
