package com.faststudio.v1.services.project;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.PublicUrl;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;

import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectDetailsTest {
    private static final String TOKEN = "test-mux-token-id-never-in-project-details";
    private static final String SECRET = "test-mux-secret-never-in-project-details";
    private static final String WEBHOOK = "test-webhook-secret-never-in-project-details";
    private static final String VIEWER = "test-viewer-secret-never-in-project-details";
    private static final String TAG = "https://pubads.g.doubleclick.net/gampad/ads?api_key=test-tag-key-never-returned";
    private static final UUID SOURCE = UUID.fromString("638c2f01-696b-4c79-8009-38c64ef8c1ab");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-project-details").toString();
        r.add("faststudio.data-dir", () -> dir); r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled"); r.add("spring.datasource.url", () -> "embedded");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Tokens tokens;
    @Autowired Secrets secrets;
    @Autowired JsonMapper json;

    UUID org(String slug) { return db.sql("select id from organization where slug=:s").param("s", slug).query(UUID.class).single(); }

    @BeforeEach void fixtures() {
        TenantScope.bypass(() -> {
            UUID org = org("aurora");
            db.sql("update organization set status='ACTIVE', features='LIBRARY_SYNC,AD_WATERFALL_VIEW,AD_OWN_TAGS' where slug in ('aurora','northwind')").update();
            db.sql("delete from mux_account where org_id=:o").param("o",org).update();
            db.sql("insert into mux_account(org_id,token_id,token_secret,webhook_secret,environment) values (:o,:i,:s,:w,'Production')")
                    .param("o",org).param("i",secrets.seal(TOKEN)).param("s",secrets.seal(SECRET)).param("w",secrets.seal(WEBHOOK)).update();
            db.sql("delete from tenant_settings where org_id=:o").param("o",org).update();
            db.sql("insert into tenant_settings(org_id,enabled_types,default_type,personalized_ads,viewer_secret) values (:o,'MOVIE,EPISODE,SHORT,AD,PROMO','MOVIE',true,:s)")
                    .param("o",org).param("s",secrets.seal(VIEWER)).update();
            db.sql("delete from ad_source where id=:id").param("id",SOURCE).update();
            db.sql("insert into ad_source(id,org_id,name,kind,tag_url,timeout_ms,position,managed_by) values (:id,:o,'Private demand','VAST',:u,1000,9,'TENANT')")
                    .param("id",SOURCE).param("o",org).param("u",TAG).update();
            return null;
        });
    }

    MvcResult call(MockHttpServletRequestBuilder request, String email, Object body) throws Exception {
        UUID user = db.sql("select id from app_user where email=:e").param("e",email).query(UUID.class).single();
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request.header("Authorization","Bearer "+tokens.mint(user,null,UUID.randomUUID()).token())).andReturn();
    }

    JsonNode ok(MvcResult response) throws Exception {
        assertEquals(200,response.getResponse().getStatus(),response.getResponse().getContentAsString());
        return json.readTree(response.getResponse().getContentAsString());
    }

    void noCredentials(MvcResult response) throws Exception {
        String body = response.getResponse().getContentAsString();
        for (String value : List.of(TOKEN, SECRET, WEBHOOK, VIEWER, TAG, "test-tag-key-never-returned", "test-mux-token"))
            assertFalse(body.contains(value), "Stored credential or credential URL reached a read response");
    }

    @Test void projectBootstrapUsesAnExplicitPrivateProjection() throws Exception {
        MvcResult response = call(get("/api/orgs/aurora/project-details"),"owner@aurora.test",null);
        JsonNode view = ok(response);
        noCredentials(response);
        assertEquals("aurora",view.path("project").path("slug").asString());
        assertEquals("OWNER",view.path("access").path("role").asString());
        assertTrue(view.path("media").path("viewerIntegrationConfigured").asBoolean());
        assertTrue(view.path("connectors").path("media").get(0).path("credentialsConfigured").asBoolean());
        assertEquals("CONFIGURED",view.path("connectors").path("media").get(0).path("state").asString());
        assertTrue(view.path("summary").path("assets").asLong()>0);
        assertTrue(response.getResponse().getHeader("Cache-Control").contains("no-store"));
        assertTrue(response.getResponse().getHeader("Vary").contains("Cookie"));
    }

    @Test void authenticationMembershipSuspensionAndPlatformScopeAreEnforced() throws Exception {
        assertEquals(401,mvc.perform(get("/api/orgs/aurora/project-details")).andReturn().getResponse().getStatus());
        assertEquals(404,call(get("/api/orgs/aurora/project-details"),"owner@northwind.test",null).getResponse().getStatus());
        String platform = "/api/platform/orgs/"+org("aurora")+"/project-details";
        assertEquals(403,call(get(platform),"owner@aurora.test",null).getResponse().getStatus());
        MvcResult admin = call(get(platform),"admin@platform.test",null);
        assertEquals("PLATFORM_ADMIN",ok(admin).path("access").path("role").asString());
        noCredentials(admin);
        db.sql("update organization set status='SUSPENDED' where slug='aurora'").update();
        assertEquals(403,call(get("/api/orgs/aurora/project-details"),"owner@aurora.test",null).getResponse().getStatus());
    }

    @Test void projectFeaturesAndRoleLimitConnectorCapabilities() throws Exception {
        JsonNode analyst = ok(call(get("/api/orgs/aurora/project-details"),"analyst@aurora.test",null));
        assertEquals(0,analyst.path("connectors").path("media").get(0).path("capabilities").size());
        db.sql("update organization set features='' where slug='aurora'").update();
        JsonNode hidden = ok(call(get("/api/orgs/aurora/project-details"),"owner@aurora.test",null));
        assertEquals(0,hidden.path("connectors").path("media").size());
        assertEquals(0,hidden.path("connectors").path("advertising").size());
        JsonNode foreign = ok(call(get("/api/orgs/northwind/project-details"),"owner@northwind.test",null));
        assertEquals("northwind",foreign.path("project").path("slug").asString());
        assertFalse(foreign.toString().contains("Private demand"));
    }

    @Test void viewerIntegrationKeyIsWriteOnlyAndRequiresPermission() throws Exception {
        String key = "replacement-viewer-integration-key-32-characters";
        String path = "/api/orgs/aurora/settings/viewer-key";
        assertEquals(403,call(post(path),"analyst@aurora.test",Map.of("key",key)).getResponse().getStatus());
        assertEquals(422,call(post(path),"owner@aurora.test",Map.of("key","too-short")).getResponse().getStatus());
        MvcResult response = call(post(path),"owner@aurora.test",Map.of("key",key));
        assertTrue(ok(response).path("viewerKeySet").asBoolean());
        assertFalse(response.getResponse().getContentAsString().contains(key));
        assertFalse(ok(response).has("key"), "The response must not contain a root signing-key field");
        String stored = TenantScope.bypass(() -> db.sql("select viewer_secret from tenant_settings where org_id=:o").param("o",org("aurora")).query(String.class).single());
        assertNotEquals(key,stored);
        assertEquals(key,secrets.open(stored));
        assertEquals(422,call(post(path),"owner@aurora.test",Map.of()).getResponse().getStatus());
    }

    @Test void legacyConnectorReadsAreRedactedAndBlankUpdatesPreserveCredentials() throws Exception {
        UUID org = org("aurora");
        MvcResult mux = call(get("/api/platform/orgs/"+org+"/connectors/mux"),"admin@platform.test",null);
        assertTrue(ok(mux).path("tokenId").isNull());
        noCredentials(mux);
        MvcResult overview = call(get("/api/platform/orgs/"+org+"/connectors"),"admin@platform.test",null);
        ok(overview); noCredentials(overview);
        MvcResult own = call(get("/api/orgs/aurora/ad-sources"),"ads@aurora.test",null);
        ok(own); noCredentials(own);
        ok(call(put("/api/orgs/aurora/ad-sources/"+SOURCE),"ads@aurora.test",
                Map.of("name","Private demand","kind","VAST","timeoutMs",1000,"enabled",false)));
        String stored = TenantScope.bypass(() -> db.sql("select tag_url from ad_source where id=:id").param("id",SOURCE).query(String.class).single());
        assertEquals(TAG,stored,"A metadata-only edit must not erase a write-only provider URL");
    }

    @Test void invalidUrlsDoNotEchoCredentialValues() {
        String credential = "credential-that-must-not-appear";
        ApiException invalid = assertThrows(ApiException.class,()->PublicUrl.check("not a url?key="+credential));
        assertFalse(invalid.getMessage().contains(credential));
        ApiException embedded = assertThrows(ApiException.class,()->PublicUrl.check("https://user:"+credential+"@example.com/video"));
        assertFalse(embedded.getMessage().contains(credential));
    }

    @Test void playerProjectionKeepsTrackingOnTheServer() throws Exception {
        var slot = new AdDecisionServiceV1.Slot(UUID.randomUUID(), "Ad", "Advertiser", "media/ad",
                30_000, java.math.BigDecimal.ONE, "VAST", Map.of("IMPRESSION", List.of(TAG)));
        var decision = new AdDecisionServiceV1.Decision(UUID.randomUUID(), 30_000, List.of(slot),
                30_000, List.of(), "Filled");
        String browser = json.writeValueAsString(AdDecisionView.from(decision));
        assertFalse(browser.contains(TAG));
        assertFalse(browser.contains("tracking"));
        assertTrue(json.writeValueAsString(decision).contains(TAG), "Server persistence must keep tracking for callbacks");
    }
}
