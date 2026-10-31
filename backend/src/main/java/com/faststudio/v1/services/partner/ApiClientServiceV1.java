package com.faststudio.v1.services.partner;

import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.partner.PartnerInterceptor;
import com.faststudio.v1.core.partner.PartnerScopes;
import com.faststudio.v1.dto.partner.PartnerDto.Client;
import com.faststudio.v1.dto.partner.PartnerDto.Created;
import com.faststudio.v1.dto.partner.PartnerDto.NewClient;
import com.faststudio.v1.repositories.partner.PartnerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Machine access for partners (docs/PARTNER_ACCESS_AND_TOKENS.md P4). A partner admin creates an API client; its secret
 * is shown once and stored as SHA-256. The client exchanges id + secret for a 10-minute "service" token
 * (OAuth 2.0 client credentials, RFC 6749 §4.4) and calls /api/partners/… with it.
 */
@Service
public class ApiClientServiceV1 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PartnerRepository repository;
    private final Tokens tokens;
    private final Audit audit;

    public ApiClientServiceV1(PartnerRepository repository, Tokens tokens, Audit audit) {
        this.repository = repository;
        this.tokens = tokens;
        this.audit = audit;
    }

    /** Only people manage clients (not other clients); creating and revoking needs a partner admin. */
    private static PartnerInterceptor.Ctx people(PartnerInterceptor.Ctx c, boolean admin) {
        if (c.userId() == null) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "API clients can't manage API clients.");
        if (admin && !c.admin()) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a partner admin can manage API clients.");
        return c;
    }

    public List<Client> list(PartnerInterceptor.Ctx ctx) {
        var c = people(ctx, false);
        return repository.clients(c.partnerId()).stream()
                .map(r -> new Client(r.id(), r.name(), List.copyOf(PartnerScopes.parse(r.scopes())), r.createdAt(), r.lastUsedAt(), r.revokedAt())).toList();
    }

    public Created create(PartnerInterceptor.Ctx ctx, NewClient r) {
        var c = people(ctx, true);
        String scopes = PartnerScopes.normalize(r.scopes());
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String secret = "fss_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = repository.insertClient(c.partnerId(), r.name().strip(), sha256(secret), scopes, c.userId());
        audit.log(c.partnerId(), c.userId(), "partner.api_client.create", "api_client", id, r.name() + ": " + scopes);
        return new Created(id, secret, List.copyOf(PartnerScopes.parse(scopes)), "/oauth/token",
                "Copy the secret now: it is shown only this once. A client only ever sees what customers share with you AND its own scopes.");
    }

    public Map<String, String> revoke(PartnerInterceptor.Ctx ctx, UUID clientId) {
        var c = people(ctx, true);
        if (!repository.revokeClient(c.partnerId(), clientId)) throw ApiException.notFound("API client");
        tokens.forgetClient(clientId);
        audit.log(c.partnerId(), c.userId(), "partner.api_client.revoke", "api_client", clientId, "");
        return Map.of("status", "REVOKED");
    }

    // ------------------------------------------------------------------ OAuth 2.0 client credentials

    /** Outcome of a token request: HTTP status plus the RFC 6749 JSON body. */
    public record TokenResponse(HttpStatus status, Map<String, Object> body) {
        static TokenResponse error(HttpStatus status, String code, String description) {
            return new TokenResponse(status, Map.of("error", code, "error_description", description));
        }
    }

    /** client_id + client_secret (form fields or HTTP Basic header) → a 10-minute Bearer token limited to the client's scopes. */
    public TokenResponse token(String grantType, String id, String secret, String scope, String authorizationHeader) {
        if (!"client_credentials".equals(grantType))
            return TokenResponse.error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "Only client_credentials is supported.");
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            String[] pair;
            try {
                pair = new String(Base64.getDecoder().decode(authorizationHeader.substring(6).strip()), StandardCharsets.UTF_8).split(":", 2);
            } catch (IllegalArgumentException e) { return invalidClient(); }   // not Base64: a client error, not a 500
            id = pair[0];
            secret = pair.length > 1 ? pair[1] : null;
        }
        UUID clientId;
        try { clientId = UUID.fromString(String.valueOf(id)); } catch (IllegalArgumentException e) { return invalidClient(); }
        var s = repository.activeClient(clientId).orElse(null);
        // Constant-time comparison of the stored hash.
        if (s == null || secret == null || !MessageDigest.isEqual(sha256(secret).getBytes(StandardCharsets.UTF_8), s.secretHash().getBytes(StandardCharsets.UTF_8)))
            return invalidClient();
        if (!"ACTIVE".equals(s.orgStatus())) return TokenResponse.error(HttpStatus.UNAUTHORIZED, "invalid_client", "This partner organization is suspended.");
        Set<String> allowed = PartnerScopes.parse(s.scopes());
        Set<String> asked = scope == null || scope.isBlank() ? allowed : PartnerScopes.parse(scope);
        if (!allowed.containsAll(asked))
            return TokenResponse.error(HttpStatus.BAD_REQUEST, "invalid_scope", "This client may only ask for: " + String.join(" ", allowed));
        String granted = String.join(" ", asked);
        Tokens.Access a = tokens.mintService(s.id(), s.name(), s.orgId(), granted);
        repository.touchClient(s.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", a.token());
        out.put("token_type", "Bearer");
        out.put("expires_in", a.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
        out.put("scope", granted);
        return new TokenResponse(HttpStatus.OK, out);
    }

    private static TokenResponse invalidClient() {
        return TokenResponse.error(HttpStatus.UNAUTHORIZED, "invalid_client", "Unknown client, wrong secret, or the client was revoked.");
    }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
