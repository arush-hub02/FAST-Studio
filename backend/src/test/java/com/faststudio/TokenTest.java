package com.faststudio;

import com.faststudio.v1_0.core.auth.TokenKeys;
import com.faststudio.v1_0.core.tenant.TenantScope;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Sign-in tokens (docs/PARTNER_ACCESS_AND_TOKENS.md phases P1-P2) end to end: claims, keys, organization IDs in paths,
 * switching, refresh rotation and theft detection, instant revocation, and the checks every request goes through.
 * Each MockHttpSession is one browser (see BrowserCookies).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pilot")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TokenTest {
    static final String ADMIN = "admin@tokens.test", ADMIN_PW = "Initial-Bootstrap-Pass-1", OWNER = "owner@alpha.test", MEMBER = "member@alpha.test";
    static final MockHttpSession admin = new MockHttpSession(), owner = new MockHttpSession(), member = new MockHttpSession();
    static String alpha, beta, memberId;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("fast-studio-tokens").toString();
        r.add("faststudio.data-dir", () -> dir);
        r.add("faststudio.db.port", () -> 0);
        r.add("faststudio.ffmpeg", () -> "disabled");
        r.add("faststudio.admin.email", () -> ADMIN);
        r.add("faststudio.admin.password", () -> ADMIN_PW);
        r.add("faststudio.media-secret", () -> "a-stable-random-secret-of-at-least-32-chars");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired JwtDecoder decoder;
    @Autowired TokenKeys keys;

    MvcResult postAs(MockHttpSession s, String path, String body) throws Exception {
        return mvc.perform(post(path).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body)).andReturn();
    }

    MvcResult getAs(MockHttpSession s, String path) throws Exception {
        return mvc.perform(get(path).session(s)).andReturn();
    }

    MvcResult bearer(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token)).andReturn();
    }

    static String cookie(MockHttpSession s, String name) {
        Cookie c = BrowserCookies.jar(s).get(name);
        return c == null ? null : c.getValue();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> claims(String jwt) {
        return json.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), Map.class);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> body(MvcResult r) throws Exception {
        return json.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> org(Map<String, Object> claims) { return (Map<String, Object>) claims.get("org"); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> orgs(Map<String, Object> claims) { return (List<Map<String, Object>>) claims.get("orgs"); }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    String login(MockHttpSession s, String email, String pw) throws Exception {
        MvcResult r = postAs(s, "/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, pw));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return cookie(s, "fs_at");
    }

    @Test @Order(1)
    void browserSignInSetsTokenCookiesWithEveryClaim() throws Exception {
        db.sql("update app_user set must_change_password = false where email = :e").param("e", ADMIN).update();
        MvcResult r = postAs(admin, "/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN, ADMIN_PW));
        assertEquals(200, r.getResponse().getStatus());
        List<String> set = r.getResponse().getHeaders("Set-Cookie");
        String at = set.stream().filter(c -> c.startsWith("fs_at=")).findFirst().orElseThrow();
        String rt = set.stream().filter(c -> c.startsWith("fs_rt=")).findFirst().orElseThrow();
        assertTrue(at.contains("HttpOnly") && at.contains("SameSite=Lax") && at.contains("Path=/;") && at.contains("Max-Age=600"), at);
        assertTrue(rt.contains("HttpOnly") && rt.contains("Path=/api/auth"), "refresh cookie only travels to /api/auth: " + rt);
        assertTrue(set.stream().noneMatch(c -> c.startsWith("JSESSIONID")), "no server session in token mode");

        String token = cookie(admin, "fs_at");
        Map<String, Object> c = claims(token);
        UUID adminId = db.sql("select id from app_user where email = :e").param("e", ADMIN).query(UUID.class).single();
        UUID platform = db.sql("select id from organization where kind = 'PLATFORM'").query(UUID.class).single();
        assertEquals("http://localhost:8080", c.get("iss"));
        assertEquals("faststudio-api", c.get("aud"), "a single audience is written as a string (RFC 7519)");
        assertEquals(adminId.toString(), c.get("sub"));
        assertEquals("user", c.get("typ"));
        assertNotNull(c.get("sid"));
        assertNotNull(c.get("jti"));
        assertEquals(600, ((Number) c.get("exp")).longValue() - ((Number) c.get("iat")).longValue());
        assertEquals(c.get("iat"), c.get("nbf"));
        assertEquals("Platform administrator", c.get("name"));
        assertEquals(ADMIN, c.get("email"));
        assertEquals(List.of("pwd"), c.get("amr"));
        assertEquals(false, c.get("pwd_change_required"));
        assertEquals(List.of("PLATFORM_ADMIN"), c.get("platform_roles"));
        assertEquals(List.of(), c.get("projects"));
        assertInstanceOf(Number.class, c.get("ver"));
        assertEquals(Map.of("id", platform.toString(), "slug", "_platform", "kind", "PLATFORM", "role", "PLATFORM_ADMIN"), org(c));
        assertNull(c.get("tid"), "the platform identity is not a customer tenant");
        assertEquals(List.of(org(c)), orgs(c));
        Map<String, Object> header = json.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[0]), Map.class);
        assertEquals("ES256", header.get("alg"));
        assertEquals(keys.signingKeyId(), header.get("kid"));
        assertEquals(200, getAs(admin, "/api/auth/me").getResponse().getStatus());
    }

    @Test @Order(2)
    @SuppressWarnings("unchecked")
    void jwksPublishesOnlyPublicKeys() throws Exception {
        String text = mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString();
        Map<String, Object> key = ((List<Map<String, Object>>) json.readValue(text, Map.class).get("keys")).getFirst();
        assertEquals("EC", key.get("kty"));
        assertEquals("P-256", key.get("crv"));
        assertEquals(keys.signingKeyId(), key.get("kid"));
        assertFalse(key.containsKey("d"), "the private part never leaves the server");
    }

    @Test @Order(3)
    void organizationIdsInPathsAndTheForcedPasswordChange() throws Exception {
        MvcResult a = postAs(admin, "/api/platform/orgs", """
                {"name":"Alpha Media","slug":"alpha","country":"IN","timezone":"Asia/Kolkata","ownerName":"Olu Owner","ownerEmail":"%s"}""".formatted(OWNER));
        assertEquals(200, a.getResponse().getStatus(), a.getResponse().getContentAsString());
        String temp = (String) body(a).get("ownerTemporaryPassword");
        alpha = (String) body(a).get("id");
        MvcResult b = postAs(admin, "/api/platform/orgs", """
                {"name":"Beta Sports","slug":"beta","country":"GB","timezone":"Europe/London","ownerName":"Olu Owner","ownerEmail":"%s"}""".formatted(OWNER));
        beta = (String) body(b).get("id");

        Map<String, Object> first = claims(login(owner, OWNER, temp));
        assertEquals(true, first.get("pwd_change_required"));
        MvcResult blocked = getAs(owner, "/api/orgs/" + alpha + "/channels");
        assertEquals(403, blocked.getResponse().getStatus());
        assertTrue(blocked.getResponse().getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"));
        assertEquals(200, getAs(owner, "/api/auth/me").getResponse().getStatus(), "the account endpoints still work");

        assertEquals(200, postAs(owner, "/api/auth/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"Chosen-Strong-Pass-4\"}".formatted(temp)).getResponse().getStatus());
        Map<String, Object> c = claims(cookie(owner, "fs_at"));
        assertEquals(false, c.get("pwd_change_required"), "a fresh token is issued for this browser");
        assertEquals(first.get("sid"), c.get("sid"), "same sign-in");
        assertEquals("alpha", org(c).get("slug"));
        assertEquals("CUSTOMER", org(c).get("kind"));
        assertEquals("OWNER", org(c).get("role"));
        assertEquals(alpha, c.get("tid"));
        assertTrue(((List<?>) c.get("perms")).contains("TEAM_MANAGE"));
        assertEquals(Set.of("alpha", "beta"), new HashSet<>(orgs(c).stream().map(o -> o.get("slug")).toList()));

        MvcResult byId = getAs(owner, "/api/orgs/" + alpha + "/channels");
        assertEquals(200, byId.getResponse().getStatus());
        assertEquals(alpha, byId.getResponse().getHeader("X-Org-Id"));
        MvcResult bySlug = getAs(owner, "/api/orgs/alpha/channels");
        assertEquals(200, bySlug.getResponse().getStatus());
        assertEquals(alpha, bySlug.getResponse().getHeader("X-Org-Id"), "slug and ID name the same organization");
        assertEquals(404, getAs(owner, "/api/orgs/" + UUID.randomUUID() + "/channels").getResponse().getStatus());
    }

    @Test @Order(4)
    void switchingTheActiveOrganization() throws Exception {
        MvcResult r = postAs(owner, "/api/auth/token/switch", "{\"org\":\"" + beta + "\"}");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        Map<String, Object> c = claims(cookie(owner, "fs_at"));
        assertEquals(beta, org(c).get("id"));
        assertEquals(beta, c.get("tid"));
        assertEquals(404, postAs(owner, "/api/auth/token/switch", "{\"org\":\"_platform\"}").getResponse().getStatus(), "only your own organizations");
        assertEquals(200, postAs(owner, "/api/auth/token/refresh", null).getResponse().getStatus());
        assertEquals(beta, org(claims(cookie(owner, "fs_at"))).get("id"), "the choice is kept when the token is renewed");
        postAs(owner, "/api/auth/token/switch", "{\"org\":\"alpha\"}");
    }

    @Test @Order(5)
    void bearerTokensForScriptsAndPartners() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Chosen-Strong-Pass-4\"}".formatted(OWNER))).andReturn();
        assertEquals(200, r.getResponse().getStatus(), "no cookies, no CSRF token needed: " + r.getResponse().getContentAsString());
        assertTrue(r.getResponse().getHeaders("Set-Cookie").stream().noneMatch(c -> c.startsWith("fs_")), "scripts get JSON, not cookies");
        Map<String, Object> t = body(r);
        assertEquals("Bearer", t.get("token_type"));
        assertEquals(600, ((Number) t.get("expires_in")).intValue(), 2);
        String access = (String) t.get("access_token");
        assertEquals(200, bearer(access, get("/api/orgs/" + alpha + "/channels")).getResponse().getStatus());
        assertEquals(200, bearer(access, post("/api/orgs/" + alpha + "/alerts/scan")).getResponse().getStatus(), "Bearer calls skip CSRF");
        MvcResult renewed = mvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + t.get("refresh_token") + "\"}")).andReturn();
        assertEquals(200, renewed.getResponse().getStatus());
        assertNotEquals(t.get("refresh_token"), body(renewed).get("refresh_token"), "a new refresh token every time");
    }

    @Test @Order(6)
    void forgedExpiredOrForeignTokensAreRefused() throws Exception {
        String good = cookie(owner, "fs_at");
        Jwt valid = decoder.decode(good);
        JwtEncoder ours = keys.encoder();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.signingKeyId()).build();
        java.util.function.Function<java.util.function.Consumer<JwtClaimsSet.Builder>, String> make = change -> {
            JwtClaimsSet.Builder b = JwtClaimsSet.builder().claims(m -> m.putAll(valid.getClaims()));
            change.accept(b);
            return ours.encode(JwtEncoderParameters.from(header, b.build())).getTokenValue();
        };
        assertEquals(200, bearer(make.apply(b -> {}), get("/api/auth/me")).getResponse().getStatus(), "control: a faithful copy works");
        String expired = make.apply(b -> b.issuedAt(Instant.now().minusSeconds(1200)).notBefore(Instant.now().minusSeconds(1200)).expiresAt(Instant.now().minusSeconds(600)));
        assertEquals(401, bearer(expired, get("/api/auth/me")).getResponse().getStatus(), "expired");
        assertEquals(401, bearer(make.apply(b -> b.audience(List.of("someone-else"))), get("/api/auth/me")).getResponse().getStatus(), "wrong audience");
        assertEquals(401, bearer(make.apply(b -> b.issuer("https://evil.example")), get("/api/auth/me")).getResponse().getStatus(), "wrong issuer");
        assertEquals(401, bearer(make.apply(b -> b.claim("typ", "service")), get("/api/auth/me")).getResponse().getStatus(), "service tokens arrive with P4");
        String[] parts = good.split("\\.");
        String otherPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("\"OWNER\"", "\"ADMIN\"").getBytes(StandardCharsets.UTF_8));
        assertEquals(401, bearer(parts[0] + "." + otherPayload + "." + parts[2], get("/api/auth/me")).getResponse().getStatus(), "tampered");
        var stranger = new ECKeyGenerator(Curve.P_256).keyID(keys.signingKeyId()).generate();
        String foreign = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(stranger))).encode(JwtEncoderParameters.from(header,
                JwtClaimsSet.builder().claims(m -> m.putAll(valid.getClaims())).build())).getTokenValue();
        assertEquals(401, bearer(foreign, get("/api/auth/me")).getResponse().getStatus(), "signed with someone else's key");
        assertEquals(401, mvc.perform(get("/api/auth/me")).andReturn().getResponse().getStatus(), "no token");
    }

    @Test @Order(7)
    void refreshRotatesAndAReplayedTokenClosesTheSignIn() throws Exception {
        MockHttpSession tab = new MockHttpSession();
        login(tab, OWNER, "Chosen-Strong-Pass-4");
        String rt1 = cookie(tab, "fs_rt");
        assertEquals(200, postAs(tab, "/api/auth/token/refresh", null).getResponse().getStatus());
        String rt2 = cookie(tab, "fs_rt");
        assertNotEquals(rt1, rt2);
        MvcResult sameMoment = mvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"" + rt1 + "\"}")).andReturn();
        assertEquals(200, sameMoment.getResponse().getStatus(), "two tabs refreshing at once is fine");
        assertNull(body(sameMoment).get("refresh_token"), "…but only the access token is renewed");

        db.sql("update refresh_token set replaced_at = now() - interval '5 minutes' where token_hash = :h").param("h", sha256(rt1)).update();
        MvcResult replay = mvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"" + rt1 + "\"}")).andReturn();
        assertEquals(401, replay.getResponse().getStatus());
        assertTrue(replay.getResponse().getContentAsString().contains("REFRESH_REUSED"));
        assertEquals(401, postAs(tab, "/api/auth/token/refresh", null).getResponse().getStatus(), "the whole sign-in is closed, newest token too");
    }

    @Test @Order(8)
    void changesToAPersonsAccessApplyAtOnce() throws Exception {
        MvcResult inv = postAs(owner, "/api/orgs/" + alpha + "/members", "{\"email\":\"%s\",\"name\":\"Mo Member\",\"role\":\"ANALYST\"}".formatted(MEMBER));
        assertEquals(200, inv.getResponse().getStatus(), inv.getResponse().getContentAsString());
        String temp = (String) body(inv).get("temporaryPassword");
        memberId = db.sql("select id::text from app_user where email = :e").param("e", MEMBER).query(String.class).single();
        login(member, MEMBER, temp);
        postAs(member, "/api/auth/password", "{\"currentPassword\":\"%s\",\"newPassword\":\"Picked-Strong-Pass-6\"}".formatted(temp));
        String old = cookie(member, "fs_at");
        assertEquals("ANALYST", org(claims(old)).get("role"));
        assertEquals(200, bearer(old, get("/api/orgs/" + alpha + "/channels")).getResponse().getStatus());

        assertEquals(200, mvc.perform(patch("/api/orgs/" + alpha + "/members/" + memberId).session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"CHANNEL_OPERATOR\"}")).andReturn().getResponse().getStatus());
        assertEquals(401, bearer(old, get("/api/orgs/" + alpha + "/channels")).getResponse().getStatus(), "the old token stops at once");
        assertEquals(200, postAs(member, "/api/auth/token/refresh", null).getResponse().getStatus());
        assertEquals("CHANNEL_OPERATOR", org(claims(cookie(member, "fs_at"))).get("role"), "the renewed token has the new role");

        // the database says member, but the token was minted before: the path's organization must be in the token
        db.sql("insert into membership(org_id, user_id, role) values (cast(:o as uuid), cast(:u as uuid), 'ANALYST')").param("o", beta).param("u", memberId).update();
        assertEquals(404, getAs(member, "/api/orgs/" + beta + "/channels").getResponse().getStatus());
        postAs(member, "/api/auth/token/refresh", null);
        assertEquals(200, getAs(member, "/api/orgs/" + beta + "/channels").getResponse().getStatus());
    }

    @Test @Order(9)
    void suspendingAnOrganizationAppliesAtOnce() throws Exception {
        String before = cookie(member, "fs_at");
        assertEquals(200, postAs(admin, "/api/platform/orgs/" + beta + "/status", "{\"status\":\"SUSPENDED\"}").getResponse().getStatus());
        assertEquals(401, bearer(before, get("/api/orgs/" + alpha + "/channels")).getResponse().getStatus(), "members' tokens are re-checked");
        postAs(member, "/api/auth/token/refresh", null);
        MvcResult r = getAs(member, "/api/orgs/" + beta + "/channels");
        assertEquals(403, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("ORG_SUSPENDED"));
        assertEquals(200, getAs(member, "/api/orgs/" + alpha + "/channels").getResponse().getStatus(), "other organizations are unaffected");
        postAs(admin, "/api/platform/orgs/" + beta + "/status", "{\"status\":\"ACTIVE\"}");
    }

    @Test @Order(10)
    void signingOutHereEverywhereOrByPasswordChange() throws Exception {
        MockHttpSession laptop = new MockHttpSession(), phone = new MockHttpSession();
        login(laptop, MEMBER, "Picked-Strong-Pass-6");
        login(phone, MEMBER, "Picked-Strong-Pass-6");
        assertEquals(200, postAs(laptop, "/api/auth/password", "{\"currentPassword\":\"Picked-Strong-Pass-6\",\"newPassword\":\"Picked-Newer-Pass-7\"}").getResponse().getStatus());
        assertEquals(200, getAs(laptop, "/api/orgs/" + alpha + "/channels").getResponse().getStatus(), "this device continues");
        assertEquals(401, getAs(phone, "/api/orgs/" + alpha + "/channels").getResponse().getStatus(), "other devices stop at once");
        assertEquals(401, postAs(phone, "/api/auth/token/refresh", null).getResponse().getStatus(), "…and can't renew");

        String rt = cookie(laptop, "fs_rt");
        MvcResult out = postAs(laptop, "/api/auth/logout", null);
        assertEquals(204, out.getResponse().getStatus());
        assertNull(cookie(laptop, "fs_at"), "cookies cleared");
        assertEquals(401, mvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"" + rt + "\"}")).andReturn().getResponse().getStatus());

        login(laptop, MEMBER, "Picked-Newer-Pass-7");
        login(phone, MEMBER, "Picked-Newer-Pass-7");
        assertEquals(204, postAs(phone, "/api/auth/logout-all", null).getResponse().getStatus());
        assertEquals(401, getAs(laptop, "/api/orgs/" + alpha + "/channels").getResponse().getStatus());
        assertEquals(401, postAs(laptop, "/api/auth/token/refresh", null).getResponse().getStatus());
    }

    @Test @Order(11)
    void platformActionsAreAuditedAsThePlatformOrganization() {
        UUID platform = db.sql("select id from organization where kind = 'PLATFORM'").query(UUID.class).single();
        int rows = TenantScope.bypass(() -> db.sql("select count(*) from audit_event where action like 'platform.%' and org_id is distinct from :p and action <> 'platform.org.create' and action not like 'platform.org.%'")
                .param("p", platform).query(Integer.class).single());
        assertEquals(0, rows, "platform administration always names the platform organization (or the org it acted on)");
        assertTrue(TenantScope.bypass(() -> db.sql("select count(*) from audit_event where org_id = :p").param("p", platform).query(Integer.class).single()) > 0);
    }
}
