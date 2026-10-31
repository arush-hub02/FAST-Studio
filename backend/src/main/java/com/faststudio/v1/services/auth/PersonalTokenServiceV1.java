package com.faststudio.v1.services.auth;

import com.faststudio.v1.core.auth.Permissions;
import com.faststudio.v1.core.auth.Throttle;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.auth.AuthDto.CreatePersonalToken;
import com.faststudio.v1.dto.auth.AuthDto.CreatedPersonalToken;
import com.faststudio.v1.dto.auth.AuthDto.PersonalToken;
import com.faststudio.v1.repositories.auth.PersonalTokenRepository;
import com.faststudio.v1.repositories.auth.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Personal access tokens: long-lived credentials a person creates for scripts and AI assistants (MCP clients), sent as
 * {@code Authorization: Bearer fsp_…}. Every person can make them, for any organization they belong to (customer or
 * partner) as at most the role chosen at creation and never more than they may do now; platform administrators can also
 * make platform-scope tokens ("platform"). On every call the token is exchanged for a normal 10-minute access token
 * (see {@link Tokens#mintPersonal}), so the usual organization, membership and row-level-security checks all apply.
 * Each reaches only the API of its scope (WebConfig) and never account settings, so a token can't make more tokens.
 */
@Service
public class PersonalTokenServiceV1 {
    public static final String PREFIX = "fsp_", PLATFORM = "platform", PLATFORM_ADMIN = "PLATFORM_ADMIN";
    static final int MAX_PER_USER = 25;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PersonalTokenRepository repository;
    private final Tokens tokens;
    private final Permissions permissions;
    private final Throttle throttle;
    private final Audit audit;
    private final UserAccountRepository users;

    public PersonalTokenServiceV1(PersonalTokenRepository repository, Tokens tokens, Permissions permissions, Throttle throttle, Audit audit,
                                  UserAccountRepository users) {
        this.users = users;
        this.repository = repository;
        this.tokens = tokens;
        this.permissions = permissions;
        this.throttle = throttle;
        this.audit = audit;
    }

    public List<PersonalToken> list(UUID user) { return repository.active(user); }

    public CreatedPersonalToken create(UUID user, CreatePersonalToken r) {
        Tokens.OrgClaim org = null;
        String ceiling;
        if (PLATFORM.equalsIgnoreCase(r.org())) {
            if (!users.user(user).platformAdmin())
                throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_TOO_HIGH", "Only platform administrators can make platform tokens.");
            if (!PLATFORM_ADMIN.equals(r.role())) throw ApiException.bad("A platform token has the role PLATFORM_ADMIN.");
            ceiling = PLATFORM_ADMIN;
        } else {
            org = tokens.orgs(user).stream().filter(o -> o.id().toString().equals(r.org()) || o.slug().equals(r.org())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Organization"));
            if (!"CUSTOMER".equals(org.kind()) && !"PARTNER".equals(org.kind())) throw ApiException.bad("Choose a customer or partner organization.");
            Tenant.Role member = Tenant.Role.valueOf(org.role()), role;
            try { role = Tenant.Role.valueOf(r.role()); } catch (IllegalArgumentException e) { throw ApiException.bad("Unknown role."); }
            if (role.partner() != member.partner() || role.rank > member.rank || !permissions.of(org.id(), user, member).containsAll(role.perms))
                throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_TOO_HIGH", "A token can't do more than your own role (" + member + ") allows. Choose a lower role.");
            ceiling = role.name();
        }
        if (repository.active(user).size() >= MAX_PER_USER)
            throw ApiException.conflict("TOO_MANY_TOKENS", "You already have " + MAX_PER_USER + " tokens. Revoke ones you no longer use.");

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expires = r.expiresInDays() == null ? null : OffsetDateTime.now().plusDays(r.expiresInDays());
        UUID id = repository.insert(user, org == null ? null : org.id(), r.name().strip(), sha256(token), token.substring(0, PREFIX.length() + 6), ceiling, expires);
        String what = r.name().strip() + " (" + ceiling + ", " + (expires == null ? "no expiry" : "expires " + expires.toLocalDate()) + ")";
        log(org == null ? null : org.id(), user, "token.personal.create", id, what);
        PersonalToken details = repository.active(user).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        return new CreatedPersonalToken(token, details);
    }

    public void revoke(UUID user, UUID id) {
        PersonalToken t = repository.active(user).stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (t == null || !repository.revoke(user, id)) throw ApiException.notFound("Token");
        tokens.forgetPersonal(id);
        log(t.orgId(), user, "token.personal.revoke", id, t.name());
    }

    /**
     * Turns a presented personal token into a short-lived access token, or null when it is unknown, revoked, expired, or
     * its owner no longer belongs to the organization (the caller then gets 401). Valid tokens always work: tokens are
     * 256-bit random values that can't be guessed, and blocking an address would also lock out everyone sharing it when one
     * old script retries a revoked token. Failures are still logged per address (token-ip in auth_attempt) for monitoring.
     */
    public String exchange(String presented, String clientAddress) {
        var usable = repository.usable(sha256(presented)).orElse(null);
        if (usable != null && usable.orgId() == null) {             // platform scope: only while still a platform administrator
            if (!users.user(usable.userId()).platformAdmin()) return failed(clientAddress);
            repository.touch(usable.id());
            return tokens.mintPersonal(usable.userId(), null, usable.id(), PLATFORM_ADMIN, null).token();
        }
        Tokens.OrgClaim org = usable == null ? null
                : tokens.orgs(usable.userId()).stream().filter(o -> o.id().equals(usable.orgId())).findFirst().orElse(null);
        if (org == null) return failed(clientAddress);
        Tenant.Role ceiling = Tenant.Role.valueOf(usable.roleKey());
        var effective = permissions.forPersonalToken(org.id(), usable.userId(), Tenant.Role.valueOf(org.role()), ceiling);
        repository.touch(usable.id());
        return tokens.mintPersonal(usable.userId(), org, usable.id(), ceiling.name(), effective).token();
    }

    private String failed(String clientAddress) {
        throttle.record(Throttle.TOKEN_IP, clientAddress);
        return null;
    }

    /**
     * In the organization's audit trail (written in its row-level-security scope: these calls have no org in the path);
     * platform tokens go to the platform's trail.
     */
    private void log(UUID org, UUID user, String action, UUID id, String detail) {
        if (org == null) audit.platform(user, action, "personal_token", id, detail);
        else TenantScope.as(org, () -> { audit.log(org, user, action, "personal_token", id, detail); return null; });
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
