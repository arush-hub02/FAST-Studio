package com.faststudio;

import com.faststudio.core.TenantScope;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Partner organizations end to end (plan P3-P4): a customer shares chosen channels and scopes, the partner accepts and
 * reads only that, the customer narrows or stops sharing with immediate effect; partner API clients get tokens with
 * client credentials that work only on the partner API, within their scopes, and stop when revoked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pilot")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartnerTest {
    static final String ADMIN = "admin@partners.test", PW = "Initial-Bootstrap-Pass-1", NEW_PW = "Chosen-Strong-Pass-4";
    static final MockHttpSession admin = new MockHttpSession(), owner = new MockHttpSession(), padmin = new MockHttpSession(), pviewer = new MockHttpSession();
    static String acme, screenbox, other, ch1, ch2, dest, link, clientId, clientSecret, serviceToken;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-partners").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("faststudio.admin.email", () -> ADMIN);
        r.add("faststudio.admin.password", () -> PW);
        r.add("faststudio.media-secret", () -> "a-stable-random-secret-of-at-least-32-chars");
        r.add("faststudio.partners.rate-limit-per-minute", () -> 60);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;

    MvcResult send(MockHttpSession s, MockHttpServletRequestBuilder b, String body) throws Exception {
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b.session(s).with(csrf())).andReturn();
    }

    MvcResult getAs(MockHttpSession s, String path) throws Exception { return send(s, get(path), null); }

    MvcResult postAs(MockHttpSession s, String path, String body) throws Exception { return send(s, post(path), body == null ? "{}" : body); }

    MvcResult bearer(String token, MockHttpServletRequestBuilder b) throws Exception { return mvc.perform(b.header("Authorization", "Bearer " + token)).andReturn(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> body(MvcResult r) throws Exception { return json.readValue(r.getResponse().getContentAsString(), Map.class); }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(MvcResult r) throws Exception { return json.readValue(r.getResponse().getContentAsString(), List.class); }

    static int status(MvcResult r) { return r.getResponse().getStatus(); }

    String newOrg(String name, String slug, String kind, String ownerName, String email) throws Exception {
        MvcResult r = postAs(admin, "/api/platform/orgs", """
                {"name":"%s","slug":"%s","country":"IN","timezone":"Asia/Kolkata","ownerName":"%s","ownerEmail":"%s","kind":"%s"}""".formatted(name, slug, ownerName, email, kind));
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        return (String) body(r).get("ownerTemporaryPassword");
    }

    void signIn(MockHttpSession s, String email, String temp) throws Exception {
        assertEquals(200, status(postAs(s, "/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, temp))));
        assertEquals(200, status(postAs(s, "/api/auth/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temp, NEW_PW))));
    }

    String src(String path) { return "/api/partners/" + screenbox + "/sources/" + acme + path; }

    @Test @Order(1)
    void platformCreatesCustomerAndPartnerOrganizations() throws Exception {
        db.sql("update app_user set must_change_password = false where email = :e").param("e", ADMIN).update();
        assertEquals(200, status(postAs(admin, "/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN, PW))));
        signIn(owner, "ann@acme.test", newOrg("Acme Media", "acme", "CUSTOMER", "Ann Owner", "ann@acme.test"));
        signIn(padmin, "pat@screenbox.test", newOrg("ScreenBox TV", "screenbox", "PARTNER", "Pat Partner", "pat@screenbox.test"));
        newOrg("Other Films", "other", "CUSTOMER", "Oz Other", "oz@other.test");
        Map<String, String> ids = new HashMap<>();
        db.sql("select slug, id::text from organization where slug in ('acme','screenbox','other')").query((rs, i) -> ids.put(rs.getString(1), rs.getString(2))).list();
        acme = ids.get("acme"); screenbox = ids.get("screenbox"); other = ids.get("other");
        assertEquals("PARTNER_ADMIN", db.sql("select role from membership where org_id = cast(:o as uuid)").param("o", screenbox).query(String.class).single());
        assertEquals(0, db.sql("select count(*) from destination where org_id = cast(:o as uuid)").param("o", screenbox).query(Integer.class).single(), "partners own no content");
        String orgs = getAs(admin, "/api/platform/orgs").getResponse().getContentAsString();
        assertTrue(orgs.contains("\"kind\":\"PARTNER\"") && orgs.contains("\"kind\":\"CUSTOMER\""));
        assertTrue(getAs(padmin, "/api/auth/me").getResponse().getContentAsString().contains("\"kind\":\"PARTNER\""));

        TenantScope.bypass(() -> {   // two channels and the partner's destination, as the wizard and Distribution page would create them
            ch1 = db.sql("insert into channel(org_id, slug, name, timezone, territories, status) values (cast(:o as uuid), 'acme-one', 'Acme One', 'Asia/Kolkata', 'IN', 'LIVE') returning id::text").param("o", acme).query(String.class).single();
            ch2 = db.sql("insert into channel(org_id, slug, name, timezone, territories) values (cast(:o as uuid), 'acme-secret', 'Acme Secret', 'Asia/Kolkata', 'IN') returning id::text").param("o", acme).query(String.class).single();
            dest = db.sql("insert into destination(org_id, name, kind, ad_mode, marker_format) values (cast(:o as uuid), 'ScreenBox app', 'PARTNER_TV', 'PASSTHROUGH', 'CUE_OUT') returning id::text").param("o", acme).query(String.class).single();
            return null;
        });
    }

    @Test @Order(2)
    void partnerOrganizationsOnlyHaveATeamAndPartnerRoles() throws Exception {
        assertEquals(404, status(getAs(padmin, "/api/orgs/screenbox/channels")), "no customer pages for partner orgs");
        assertEquals(200, status(getAs(padmin, "/api/orgs/screenbox/members")));
        assertEquals(422, status(postAs(padmin, "/api/orgs/screenbox/members", "{\"email\":\"x@screenbox.test\",\"name\":\"X\",\"role\":\"ANALYST\"}")), "customer roles don't exist here");
        MvcResult inv = postAs(padmin, "/api/orgs/screenbox/members", "{\"email\":\"val@screenbox.test\",\"name\":\"Val Viewer\",\"role\":\"PARTNER_VIEWER\"}");
        assertEquals(200, status(inv));
        signIn(pviewer, "val@screenbox.test", (String) body(inv).get("temporaryPassword"));
        assertEquals(422, status(postAs(owner, "/api/orgs/acme/members", "{\"email\":\"y@acme.test\",\"name\":\"Y\",\"role\":\"PARTNER_VIEWER\"}")), "partner roles don't exist in customer orgs");
    }

    @Test @Order(3)
    void customerSharesAndPartnerAccepts() throws Exception {
        String dir = getAs(owner, "/api/orgs/acme/partners/directory").getResponse().getContentAsString();
        assertTrue(dir.contains("ScreenBox TV") && dir.contains("epg:read"));
        MvcResult r = postAs(owner, "/api/orgs/acme/partners", """
                {"partnerOrgId":"%s","allChannels":false,"channelIds":["%s"],"scopes":["epg:read","channels:read","stream:read"],"destinationId":"%s","note":"Pilot"}"""
                .formatted(screenbox, ch1, dest));
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        link = (String) body(r).get("id");
        assertEquals("INVITED", body(r).get("status"));
        assertEquals(List.of("channels:read", "epg:read", "stream:read"), body(r).get("scopes"), "scopes are stored in a fixed order");
        assertEquals(1, db.sql("select count(*) from email_message where template = 'partner_shared' and to_email = 'pat@screenbox.test'").query(Integer.class).single(), "partner admins are emailed");
        assertEquals(409, status(postAs(owner, "/api/orgs/acme/partners", "{\"partnerOrgId\":\"%s\",\"allChannels\":true,\"scopes\":[\"epg:read\"]}".formatted(screenbox))));
        assertEquals(404, status(postAs(owner, "/api/orgs/acme/partners", "{\"partnerOrgId\":\"%s\",\"allChannels\":true,\"scopes\":[\"epg:read\"]}".formatted(other))), "only partner orgs");

        assertEquals(404, status(getAs(padmin, src("/channels"))), "nothing until accepted");
        assertEquals("INVITED", list(getAs(padmin, "/api/partners/screenbox/links")).getFirst().get("status"));
        assertEquals(403, status(postAs(pviewer, "/api/partners/screenbox/links/" + link + "/accept", null)), "viewers can't accept");
        assertEquals(200, status(postAs(padmin, "/api/partners/screenbox/links/" + link + "/accept", null)));
    }

    @Test @Order(4)
    void partnerReadsOnlyWhatIsShared() throws Exception {
        List<Map<String, Object>> channels = list(getAs(pviewer, src("/channels")));
        assertEquals(List.of("Acme One"), channels.stream().map(c -> c.get("name")).toList(), "only the shared channel");
        assertEquals(200, status(getAs(pviewer, src("/channels/" + ch1))));
        assertEquals(404, status(getAs(pviewer, src("/channels/" + ch2))), "unshared channel");
        assertEquals(200, status(getAs(pviewer, src("/channels/" + ch1 + "/epg"))));
        MvcResult xml = getAs(pviewer, src("/channels/" + ch1 + "/epg.xml"));
        assertTrue(xml.getResponse().getContentAsString().contains("<channel id=\"acme-one.acme\">"));
        Map<String, Object> stream = body(getAs(pviewer, src("/channels/" + ch1 + "/stream")));
        assertEquals("POST", stream.get("method"));
        assertEquals(dest, ((Map<?, ?>) stream.get("body")).get("destinationId"));
        assertEquals("PASSTHROUGH", ((Map<?, ?>) stream.get("destination")).get("adMode"));
        assertEquals(404, status(getAs(pviewer, src("/channels/" + ch1 + "/breaks"))), "markers not shared");
        assertEquals(404, status(getAs(pviewer, src("/channels/" + ch1 + "/delivery"))), "delivery not shared");
        assertEquals(404, status(getAs(pviewer, "/api/partners/screenbox/sources/" + other + "/channels")), "a customer that shares nothing");
        assertEquals(404, status(getAs(owner, src("/channels"))), "customers can't use the partner's API");

        String audit = getAs(owner, "/api/orgs/acme/audit").getResponse().getContentAsString();
        assertTrue(audit.contains("partner.read") && audit.contains("Val Viewer (ScreenBox TV)"), "the customer sees every partner read");
        List<Map<String, Object>> sharing = list(getAs(owner, "/api/orgs/acme/partners"));
        assertEquals("ACTIVE", sharing.getFirst().get("status"));
        assertTrue(((Number) sharing.getFirst().get("readsLast7Days")).intValue() >= 5);
    }

    @Test @Order(5)
    void narrowingTakesEffectAtOnce() throws Exception {
        assertEquals(200, status(send(owner, put("/api/orgs/acme/partners/" + link), """
                {"allChannels":false,"channelIds":["%s"],"scopes":["channels:read","stream:read"],"destinationId":"%s"}""".formatted(ch1, dest))));
        assertEquals(404, status(getAs(pviewer, src("/channels/" + ch1 + "/epg"))), "guide no longer shared");
        assertEquals(200, status(getAs(pviewer, src("/channels"))));
    }

    @Test @Order(6)
    void apiClientsUseClientCredentials() throws Exception {
        assertEquals(403, status(postAs(pviewer, "/api/partners/screenbox/clients", "{\"name\":\"x\",\"scopes\":[\"channels:read\"]}")), "admins only");
        MvcResult c = postAs(padmin, "/api/partners/screenbox/clients", "{\"name\":\"Guide sync\",\"scopes\":[\"channels:read\"]}");
        assertEquals(200, status(c), c.getResponse().getContentAsString());
        clientId = (String) body(c).get("clientId");
        clientSecret = (String) body(c).get("clientSecret");
        assertTrue(clientSecret.startsWith("fss_"));
        assertFalse(getAs(padmin, "/api/partners/screenbox/clients").getResponse().getContentAsString().contains(clientSecret), "the secret is never shown again");

        MvcResult t = mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("client_id", clientId).param("client_secret", clientSecret)).andReturn();
        assertEquals(200, status(t), t.getResponse().getContentAsString());
        serviceToken = (String) body(t).get("access_token");
        assertEquals("channels:read", body(t).get("scope"));
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        assertEquals(200, status(mvc.perform(post("/oauth/token").header("Authorization", "Basic " + basic).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")).andReturn()), "HTTP Basic works too");
        assertEquals(401, status(mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("client_id", clientId).param("client_secret", "wrong")).andReturn()));
        assertEquals(400, status(mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("client_id", clientId).param("client_secret", clientSecret).param("scope", "stream:read")).andReturn()), "only its own scopes");

        assertEquals(200, status(bearer(serviceToken, get(src("/channels")))));
        assertEquals(404, status(bearer(serviceToken, get(src("/channels/" + ch1 + "/stream")))), "shared, but not in this client's scopes");
        assertEquals(403, status(bearer(serviceToken, get("/api/auth/me"))), "service tokens only work on the partner API");
        assertEquals(403, status(bearer(serviceToken, get("/api/orgs/acme/channels"))));
        assertEquals(403, status(bearer(serviceToken, post("/api/partners/screenbox/clients").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"scopes\":[\"epg:read\"]}"))), "clients can't make clients");

        assertEquals(200, status(postAs(padmin, "/api/partners/screenbox/clients/" + clientId + "/revoke", null)));
        assertEquals(401, status(bearer(serviceToken, get(src("/channels")))), "a revoked client's token stops at once");
        assertEquals(401, status(mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("client_id", clientId).param("client_secret", clientSecret)).andReturn()));
    }

    @Test @Order(7)
    void stoppingSharingTakesEffectAtOnce() throws Exception {
        assertEquals(200, status(postAs(owner, "/api/orgs/acme/partners/" + link + "/revoke", null)));
        assertEquals(404, status(getAs(pviewer, src("/channels"))), "the partner loses access on its next request");
        assertEquals(List.of(), list(getAs(pviewer, "/api/partners/screenbox/links")), "revoked sharings disappear for the partner");
        assertEquals("REVOKED", list(getAs(owner, "/api/orgs/acme/partners")).getFirst().get("status"), "the customer keeps the record");
        assertEquals(200, status(postAs(owner, "/api/orgs/acme/partners", "{\"partnerOrgId\":\"%s\",\"allChannels\":true,\"scopes\":[\"epg:read\"]}".formatted(screenbox))),
                "a stopped sharing can be replaced by a new one");
    }

    @Test @Order(8)
    void eachPartnerIsRateLimited() throws Exception {
        int limited = 0;
        for (int i = 0; i < 70 && limited == 0; i++) if (status(getAs(padmin, "/api/partners/screenbox/links")) == 429) limited = i;
        assertTrue(limited > 0, "the 60-per-minute limit applies");
    }
}
