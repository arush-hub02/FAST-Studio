package com.faststudio.v1.controllers.partner;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.partner.PartnerInterceptor;
import com.faststudio.v1.core.partner.PartnerScopes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Machine access for partners (docs/PARTNER_ACCESS_AND_TOKENS.md P4). A partner admin creates an API client; its
 * secret is shown once and stored as SHA-256. The client exchanges id + secret for a 10-minute "service" token at
 * POST /oauth/token (OAuth 2.0 client credentials, RFC 6749 §4.4) and calls /api/partners/… with it.
 */
@RestController
public class ApiClientControllerV1 {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;
    private final Tokens tokens;
    private final Audit audit;

    public ApiClientControllerV1(JdbcClient db, Tokens tokens, Audit audit) {
        this.db = db;
        this.tokens = tokens;
        this.audit = audit;
    }

    public record Client(UUID id, String name, List<String> scopes, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {}

    record Row(UUID id, String name, String scopes, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {}

    private static PartnerInterceptor.Ctx people(HttpServletRequest req, boolean admin) {
        var c = PartnerApiControllerV1.ctx(req);
        if (c.userId() == null) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "API clients can't manage API clients.");
        if (admin && !c.admin()) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a partner admin can manage API clients.");
        return c;
    }

    @GetMapping("/api/partners/{partner}/clients")
    List<Client> list(HttpServletRequest req) {
        var c = people(req, false);
        return db.sql("select id, name, scopes, created_at, last_used_at, revoked_at from api_client where org_id = :o order by revoked_at nulls first, created_at desc")
                .param("o", c.partnerId()).query(Row.class).list().stream()
                .map(r -> new Client(r.id(), r.name(), List.copyOf(PartnerScopes.parse(r.scopes())), r.createdAt(), r.lastUsedAt(), r.revokedAt())).toList();
    }

    public record NewClient(@NotBlank @Size(max = 60) String name, List<String> scopes) {}

    public record Created(UUID clientId, String clientSecret, List<String> scopes, String tokenUrl, String note) {}

    @PostMapping("/api/partners/{partner}/clients")
    Created create(@Valid @RequestBody NewClient r, HttpServletRequest req) {
        var c = people(req, true);
        String scopes = PartnerScopes.normalize(r.scopes());
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String secret = "fss_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = db.sql("insert into api_client(org_id, name, secret_hash, scopes, created_by) values (:o,:n,:h,:s,:u) returning id")
                .param("o", c.partnerId()).param("n", r.name().strip()).param("h", sha256(secret)).param("s", scopes).param("u", c.userId())
                .query(UUID.class).single();
        audit.log(c.partnerId(), c.userId(), "partner.api_client.create", "api_client", id, r.name() + ": " + scopes);
        return new Created(id, secret, List.copyOf(PartnerScopes.parse(scopes)), "/oauth/token",
                "Copy the secret now: it is shown only this once. A client only ever sees what customers share with you AND its own scopes.");
    }

    @PostMapping("/api/partners/{partner}/clients/{clientId}/revoke")
    Map<String, String> revoke(@PathVariable UUID clientId, HttpServletRequest req) {
        var c = people(req, true);
        int n = db.sql("update api_client set revoked_at = now() where id = :id and org_id = :o and revoked_at is null").param("id", clientId).param("o", c.partnerId()).update();
        if (n == 0) throw ApiException.notFound("API client");
        tokens.forgetClient(clientId);
        audit.log(c.partnerId(), c.userId(), "partner.api_client.revoke", "api_client", clientId, "");
        return Map.of("status", "REVOKED");
    }

    // ------------------------------------------------------------------ OAuth 2.0 client credentials

    record Secret(UUID id, UUID orgId, String name, String secretHash, String scopes, String orgStatus) {}

    /** RFC 6749 §4.4: client_id + client_secret (form fields or HTTP Basic) → a 10-minute Bearer token. */
    @PostMapping(value = "/oauth/token", consumes = {"application/x-www-form-urlencoded", "multipart/form-data"})
    ResponseEntity<Map<String, Object>> token(@RequestParam("grant_type") String grantType, @RequestParam(value = "client_id", required = false) String id,
                                              @RequestParam(value = "client_secret", required = false) String secret,
                                              @RequestParam(value = "scope", required = false) String scope, HttpServletRequest req) {
        if (!"client_credentials".equals(grantType)) return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "Only client_credentials is supported.");
        String basic = req.getHeader("Authorization");
        if (basic != null && basic.regionMatches(true, 0, "Basic ", 0, 6)) {
            String[] pair = new String(Base64.getDecoder().decode(basic.substring(6).strip()), StandardCharsets.UTF_8).split(":", 2);
            id = pair[0];
            secret = pair.length > 1 ? pair[1] : null;
        }
        UUID clientId;
        try { clientId = UUID.fromString(String.valueOf(id)); } catch (IllegalArgumentException e) { return invalidClient(); }
        Secret s = db.sql("""
                select c.id, c.org_id, c.name, c.secret_hash, c.scopes, o.status as org_status from api_client c join organization o on o.id = c.org_id
                where c.id = :id and c.revoked_at is null and o.kind = 'PARTNER'""").param("id", clientId).query(Secret.class).optional().orElse(null);
        if (s == null || secret == null || !MessageDigest.isEqual(sha256(secret).getBytes(StandardCharsets.UTF_8), s.secretHash().getBytes(StandardCharsets.UTF_8)))
            return invalidClient();
        if (!"ACTIVE".equals(s.orgStatus())) return error(HttpStatus.UNAUTHORIZED, "invalid_client", "This partner organization is suspended.");
        Set<String> allowed = PartnerScopes.parse(s.scopes());
        Set<String> asked = scope == null || scope.isBlank() ? allowed : PartnerScopes.parse(scope);
        if (!allowed.containsAll(asked)) return error(HttpStatus.BAD_REQUEST, "invalid_scope", "This client may only ask for: " + String.join(" ", allowed));
        String granted = String.join(" ", asked);
        Tokens.Access a = tokens.mintService(s.id(), s.name(), s.orgId(), granted);
        db.sql("update api_client set last_used_at = now() where id = :id").param("id", s.id()).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", a.token());
        out.put("token_type", "Bearer");
        out.put("expires_in", a.expiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond());
        out.put("scope", granted);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(out);
    }

    private static ResponseEntity<Map<String, Object>> invalidClient() {
        return error(HttpStatus.UNAUTHORIZED, "invalid_client", "Unknown client, wrong secret, or the client was revoked.");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String description) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", code, "error_description", description));
    }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
