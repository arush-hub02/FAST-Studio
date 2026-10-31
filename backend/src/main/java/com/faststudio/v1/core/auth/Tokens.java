package com.faststudio.v1.core.auth;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.tenant.Tenant;


import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in tokens (docs/PARTNER_ACCESS_AND_TOKENS.md §4). A 10-minute ES256 access token says who the person is and
 * which organizations they work in, with what role; a refresh token (random, stored as SHA-256, one family per
 * sign-in) renews it. Browsers get both as HttpOnly cookies; scripts send "Authorization: Bearer".
 * The database stays the authority: tokens are minted from it, the version check revokes them at once, and the
 * TenantInterceptor and row-level security still check every request.
 */
@Component
public class Tokens {
    public static final String ACCESS_COOKIE = "fs_at", REFRESH_COOKIE = "fs_rt", AUDIENCE = "faststudio-api";
    static final Duration ACCESS_TTL = Duration.ofMinutes(10), REFRESH_IDLE = Duration.ofHours(12), REFRESH_MAX = Duration.ofDays(7),
            REUSE_GRACE = Duration.ofSeconds(30), VERSION_CACHE = Duration.ofSeconds(30);
    static final int MAX_ORGS = 20;
    /** API paths that never read the access cookie: public ones, and those that work from the refresh cookie. */
    public static final Set<String> COOKIE_FREE = Set.of("/api/auth/login", "/api/auth/csrf", "/api/auth/demo-accounts", "/api/auth/reset",
            "/api/auth/forgot", "/api/auth/options", "/api/auth/token", "/api/auth/token/refresh", "/api/auth/logout");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final Permissions permissions;
    private final TokenKeys keys;
    private final JwtEncoder encoder;
    private final String issuer;
    private final boolean secureCookies, enabled;
    // ponytail: per-instance cache; with several servers, bump() must also reach the others (or shorten the 30 s)
    private final Map<UUID, long[]> versions = new ConcurrentHashMap<>();   // user -> {version, fetched at ms}

    public Tokens(JdbcClient db, Permissions permissions, TokenKeys keys, @Value("${faststudio.app-url}") String appUrl,
                  @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies, @Value("${faststudio.auth.mode:jwt}") String mode) {
        this.db = db;
        this.permissions = permissions;
        this.keys = keys;
        this.encoder = keys.encoder();
        this.issuer = appUrl.replaceAll("/+$", "");
        this.secureCookies = secureCookies;
        this.enabled = !"session".equalsIgnoreCase(mode);
    }

    /** False when faststudio.auth.mode=session (the previous server-session sign-in, kept for one release). */
    public boolean enabled() { return enabled; }

    // ---------------------------------------------------------------- access tokens

    public record OrgClaim(UUID id, UUID tenantId, String slug, String kind, String role) {}

    record UserRow(UUID id, String name, String email, boolean platformAdmin, boolean mustChangePassword, int tokenVersion) {}

    /** Every organization the person works in (platform admins also act as the platform organization). */
    public List<OrgClaim> orgs(UUID userId) {
        boolean admin = db.sql("select platform_admin from app_user where id = :u").param("u", userId).query(Boolean.class).single();
        List<OrgClaim> list = new ArrayList<>(db.sql("""
                select o.id, o.tenant_id, o.slug, o.kind, m.role from membership m join organization o on o.id = m.org_id
                where m.user_id = :u order by o.name""").param("u", userId).query(OrgClaim.class).list());
        if (admin) db.sql("select id, tenant_id, slug, kind, 'PLATFORM_ADMIN' as role from organization where kind = 'PLATFORM'")
                .query(OrgClaim.class).optional().ifPresent(list::add);
        return list;
    }

    public record Access(String token, Instant expiresAt, OrgClaim org) {}

    /** Mints an access token from the database as it is now. activeOrg is kept if the person still belongs to it. */
    public Access mint(UUID userId, UUID activeOrg, UUID sid) {
        UserRow u = db.sql("select id, name, email, platform_admin, must_change_password, token_version from app_user where id = :id")
                .param("id", userId).query(UserRow.class).single();
        List<OrgClaim> orgs = orgs(userId);
        OrgClaim active = orgs.stream().filter(o -> o.id().equals(activeOrg)).findFirst()
                .orElse(orgs.stream().filter(o -> o.kind().equals("CUSTOMER")).findFirst().orElse(orgs.isEmpty() ? null : orgs.getFirst()));
        Instant now = Instant.now(), exp = now.plus(ACCESS_TTL);
        JwtClaimsSet.Builder c = JwtClaimsSet.builder().issuer(issuer).audience(List.of(AUDIENCE)).subject(userId.toString())
                .issuedAt(now).notBefore(now).expiresAt(exp).id(UUID.randomUUID().toString())
                .claim("typ", "user").claim("sid", sid.toString()).claim("name", u.name()).claim("email", u.email())
                .claim("amr", List.of("pwd")).claim("pwd_change_required", u.mustChangePassword())
                .claim("platform_roles", u.platformAdmin() ? List.of("PLATFORM_ADMIN") : List.of())
                .claim("projects", List.of())           // project-scoped access arrives later (plan P5); empty = none yet
                .claim("ver", u.tokenVersion());
        if (active != null) {
            c.claim("org", claim(active)).claim("perms", active.role().equals("PLATFORM_ADMIN")?List.of():
                    permissions.of(active.id(),userId,Tenant.Role.valueOf(active.role())).stream().map(Enum::name).sorted().toList());
            if (active.tenantId() != null) c.claim("tid", active.tenantId().toString());
        }
        else c.claim("perms", List.of());
        if (orgs.size() <= MAX_ORGS) c.claim("orgs", orgs.stream().map(Tokens::claim).toList());
        else c.claim("orgs_truncated", true);    // clients read /api/auth/me instead
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.signingKeyId()).type("JWT").build();
        return new Access(encoder.encode(JwtEncoderParameters.from(header, c.build())).getTokenValue(), exp, active);
    }

    public static Map<String, Object> claim(OrgClaim o) {
        return Map.of("id", o.id().toString(), "slug", o.slug(), "kind", o.kind(), "role", o.role());
    }

    static List<String> perms(String role) {
        try { return Tenant.Role.valueOf(role).perms.stream().map(Enum::name).sorted().toList(); }
        catch (IllegalArgumentException e) { return List.of(); }      // PLATFORM_ADMIN: platform_roles says it
    }

    /** Signature (in the decoder), then expiry, issuer, audience, type and token version. */
    public OAuth2TokenValidator<Jwt> validator() {
        OAuth2TokenValidator<Jwt> current = jwt -> current(jwt) ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "This sign-in is out of date (password, role or access changed). Refresh it.", null));
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE)), current);
    }

    boolean current(Jwt jwt) {
        String typ = jwt.getClaimAsString("typ");
        if ("service".equals(typ)) return clientActive(UUID.fromString(jwt.getSubject()));
        if (!"user".equals(typ)) return false;
        return jwt.getClaim("ver") instanceof Number n && n.intValue() == version(UUID.fromString(jwt.getSubject()));
    }

    private final Map<UUID, long[]> clients = new ConcurrentHashMap<>();    // API client -> {active 1/0, fetched at ms}

    boolean clientActive(UUID client) {
        long now = System.currentTimeMillis();
        long[] v = clients.get(client);
        if (v != null && now - v[1] < VERSION_CACHE.toMillis()) return v[0] == 1;
        boolean active = db.sql("select count(*) from api_client where id = :c and revoked_at is null").param("c", client).query(Integer.class).single() > 0;
        clients.put(client, new long[]{active ? 1 : 0, now});
        return active;
    }

    /** A revoked API client's tokens stop working at once. */
    public void forgetClient(UUID client) { clients.remove(client); }

    /**
     * Token for a partner's machine (OAuth2 client credentials): typ "service", the partner org, and only the scopes the
     * client was given. It works only under /api/partners (WebConfig), never for people's pages.
     */
    public Access mintService(UUID clientId, String clientName, UUID partnerOrg, String scope) {
        OrgClaim org = db.sql("select id, tenant_id, slug, kind, 'SERVICE' as role from organization where id = :o").param("o", partnerOrg).query(OrgClaim.class).single();
        Instant now = Instant.now(), exp = now.plus(ACCESS_TTL);
        JwtClaimsSet c = JwtClaimsSet.builder().issuer(issuer).audience(List.of(AUDIENCE)).subject(clientId.toString())
                .issuedAt(now).notBefore(now).expiresAt(exp).id(UUID.randomUUID().toString())
                .claim("typ", "service").claim("client_name", clientName).claim("scope", scope)
                .claim("org", claim(org)).build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.signingKeyId()).type("JWT").build();
        return new Access(encoder.encode(JwtEncoderParameters.from(header, c)).getTokenValue(), exp, org);
    }

    public static boolean isService() {
        Jwt j = current();
        return j != null && "service".equals(j.getClaimAsString("typ"));
    }

    int version(UUID user) {
        long now = System.currentTimeMillis();
        long[] v = versions.get(user);
        if (v != null && now - v[1] < VERSION_CACHE.toMillis()) return (int) v[0];
        int ver = db.sql("select token_version from app_user where id = :u").param("u", user).query(Integer.class).optional().orElse(-1);
        versions.put(user, new long[]{ver, now});
        return ver;
    }

    /** What these people's tokens say is now wrong (role, membership, password): their access tokens stop working at once. */
    public void bump(Collection<UUID> users) {
        if (users.isEmpty()) return;
        db.sql("update app_user set token_version = token_version + 1 where id in (:ids)").param("ids", users).update();
        users.forEach(versions::remove);
    }

    public void bumpOrg(UUID orgId) {
        bump(db.sql("select user_id from membership where org_id = :o").param("o", orgId).query(UUID.class).list());
    }

    // ---------------------------------------------------------------- refresh tokens

    public record Refresh(String token, Instant expiresAt) {}

    record RefreshRow(UUID familyId, UUID userId, UUID activeOrgId, OffsetDateTime familyStartedAt, OffsetDateTime expiresAt,
                      OffsetDateTime replacedAt, OffsetDateTime revokedAt) {}

    public record Renewed(UUID userId, UUID sid, UUID activeOrg, Refresh refresh) {}

    @Transactional
    public Refresh startFamily(UUID userId, UUID sid, UUID activeOrg) {
        return insert(sid, userId, activeOrg, Instant.now());
    }

    private Refresh insert(UUID family, UUID user, UUID activeOrg, Instant familyStart) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant idle = Instant.now().plus(REFRESH_IDLE), max = familyStart.plus(REFRESH_MAX);
        Instant exp = idle.isBefore(max) ? idle : max;
        db.sql("insert into refresh_token(token_hash, family_id, user_id, active_org_id, family_started_at, expires_at) values (:h,:f,:u,:o,:s,:e)")
                .param("h", Accounts.sha256(token)).param("f", family).param("u", user).param("o", activeOrg)
                .param("s", OffsetDateTime.ofInstant(familyStart, java.time.ZoneOffset.UTC)).param("e", OffsetDateTime.ofInstant(exp, java.time.ZoneOffset.UTC)).update();
        return new Refresh(token, exp);
    }

    /**
     * Uses a refresh token once and returns its replacement. Using an already-replaced token again means it was copied:
     * the whole sign-in is closed. Two tabs refreshing in the same moment are allowed (30 s grace; only a new access
     * token is given, the browser already holds the newest refresh cookie).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Renewed rotate(String raw) {
        RefreshRow r = raw == null ? null : db.sql("""
                select family_id, user_id, active_org_id, family_started_at, expires_at, replaced_at, revoked_at
                from refresh_token where token_hash = :h for update""").param("h", Accounts.sha256(raw)).query(RefreshRow.class).optional().orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        if (r == null || r.revokedAt() != null || r.expiresAt().isBefore(now)) throw signInAgain("REFRESH_INVALID", "Your sign-in has ended. Please sign in again.");
        if (r.replacedAt() != null) {
            boolean familyAlive = db.sql("select count(*) from refresh_token where family_id = :f and revoked_at is null and replaced_at is null and expires_at > now()")
                    .param("f", r.familyId()).query(Integer.class).single() > 0;
            if (familyAlive && r.replacedAt().isAfter(now.minus(REUSE_GRACE))) return new Renewed(r.userId(), r.familyId(), r.activeOrgId(), null);
            revokeFamily(r.familyId());
            throw signInAgain("REFRESH_REUSED", "This sign-in was used from somewhere else, so it was closed for your safety. Please sign in again.");
        }
        db.sql("update refresh_token set replaced_at = now() where token_hash = :h").param("h", Accounts.sha256(raw)).update();
        return new Renewed(r.userId(), r.familyId(), r.activeOrgId(), insert(r.familyId(), r.userId(), r.activeOrgId(), r.familyStartedAt().toInstant()));
    }

    static ApiException signInAgain(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public UUID activeOrg(UUID sid) {
        return db.sql("select active_org_id from refresh_token where family_id = :f and revoked_at is null order by created_at desc limit 1")
                .param("f", sid).query(UUID.class).optional().orElse(null);
    }

    public void setActiveOrg(UUID sid, UUID org) {
        db.sql("update refresh_token set active_org_id = :o where family_id = :f and revoked_at is null").param("o", org).param("f", sid).update();
    }

    public void revokeFamily(UUID sid) {
        db.sql("update refresh_token set revoked_at = now() where family_id = :f and revoked_at is null").param("f", sid).update();
    }

    public void revokeByToken(String raw) {
        if (raw != null) db.sql("update refresh_token set revoked_at = now() where revoked_at is null and family_id = (select family_id from refresh_token where token_hash = :h)")
                .param("h", Accounts.sha256(raw)).update();
    }

    /** Closes every sign-in of this person, except the one with sid keep (may be null). */
    public void revokeAll(UUID user, UUID keep) {
        db.sql("update refresh_token set revoked_at = now() where user_id = :u and revoked_at is null and family_id is distinct from cast(:k as uuid)")
                .param("u", user).param("k", keep).update();
    }

    // ---------------------------------------------------------------- browser cookies and request plumbing

    /** New browser sign-in: a new refresh family plus both cookies. Returns the access token's active org. */
    public OrgClaim signIn(UUID userId, HttpServletResponse res) {
        UUID sid = UUID.randomUUID();
        Access a = mint(userId, null, sid);
        Refresh r = startFamily(userId, sid, a.org() == null ? null : a.org().id());
        setCookies(res, a, r);
        return a.org();
    }

    /** A fresh access token for this sign-in (same sid), e.g. after the person changed their own password. */
    public void reissue(UUID userId, UUID sid, HttpServletResponse res) {
        setCookies(res, mint(userId, activeOrg(sid), sid), null);
    }

    public void setCookies(HttpServletResponse res, Access a, Refresh r) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, a.token(), "/", ACCESS_TTL).toString());
        if (r != null) res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, r.token(), "/api/auth", Duration.between(Instant.now(), r.expiresAt())).toString());
    }

    public void clearCookies(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, "", "/", Duration.ZERO).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", "/api/auth", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secureCookies).sameSite("Lax").path(path).maxAge(maxAge).build();
    }

    public static String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (c.getName().equals(name) && !c.getValue().isEmpty()) return c.getValue();
        return null;
    }

    /** Where the access token comes from: an Authorization header, else the cookie (only on API paths that need sign-in). */
    public String resolve(HttpServletRequest req) {
        String h = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) return h.substring(7).strip();
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (!path.startsWith("/api/") || COOKIE_FREE.contains(path)) return null;
        return cookie(req, ACCESS_COOKIE);
    }

    /** The current request's verified token, or null (session mode, or a test's mock user). */
    public static Jwt current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof JwtAuthenticationToken j ? j.getToken() : null;
    }

    public static UUID currentSid() {
        Jwt j = current();
        return j == null || j.getClaimAsString("sid") == null ? null : UUID.fromString(j.getClaimAsString("sid"));
    }
}
