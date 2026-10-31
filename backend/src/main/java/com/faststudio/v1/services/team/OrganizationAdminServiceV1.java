package com.faststudio.v1.services.team;

import com.faststudio.v1.core.auth.Accounts;
import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.team.TeamDto.*;
import com.faststudio.v1.repositories.team.MembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;

/**
 * Platform administration of organizations: list with usage counts, create a customer or partner organization with
 * its first owner, suspend/re-activate, and reset any account's password. Platform administrators only.
 */
@Service
public class OrganizationAdminServiceV1 {
    private final MembershipRepository repository;
    private final AccountEmailService emails;
    private final PasswordEncoder encoder;
    private final Accounts accounts;
    private final Tokens tokens;
    private final Audit audit;

    public OrganizationAdminServiceV1(MembershipRepository repository, AccountEmailService emails, PasswordEncoder encoder,
                                        Accounts accounts, Tokens tokens, Audit audit) {
        this.repository = repository;
        this.emails = emails;
        this.encoder = encoder;
        this.accounts = accounts;
        this.tokens = tokens;
        this.audit = audit;
    }

    private void requirePlatform() {
        if (!repository.isPlatformAdmin(Auth.userId())) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
    }

    public List<OrgSummary> organizations() {
        requirePlatform();
        return repository.organizationSummaries();
    }

    /** Creates the organization and its first owner (or partner admin); returns id, one-time password (new accounts) and a note. */
    @Transactional
    public Map<String, Object> create(CreateOrg r) {
        requirePlatform();
        try { ZoneId.of(r.timezone()); } catch (Exception e) { throw ApiException.bad("Unknown timezone."); }
        if (repository.slugTaken(r.slug())) throw ApiException.conflict("SLUG_TAKEN", "That organization ID is taken.");
        String kind = r.kind() == null ? "CUSTOMER" : r.kind();
        Tenant.Role top = Tenant.Role.top(kind);
        // Legacy per-organization channel cap; the tenant's subscription plan is the enforced limit.
        UUID org = repository.createOrganization(r.slug(), r.name(), r.country(), r.timezone(), kind, kind.equals("PARTNER") ? 0 : 10);

        String email = r.ownerEmail().toLowerCase(Locale.ROOT);
        String temporaryPassword = null;
        UUID uid = repository.userByEmail(email).orElse(null);
        if (uid == null) {
            temporaryPassword = Accounts.temporaryPassword();
            uid = repository.createUser(email, r.ownerName(), encoder.encode(temporaryPassword));
        }
        repository.addMember(org, uid, top.name(), null);
        tokens.bump(List.of(uid));
        if (kind.equals("CUSTOMER")) repository.createWebPlayerDestination(org);
        audit.log(org, Auth.userId(), "platform.org.create", "organization", org, r.name() + (kind.equals("PARTNER") ? " (partner)" : ""));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", org);
        out.put("ownerTemporaryPassword", temporaryPassword);
        out.put("note", emails.welcome(org, uid, email, temporaryPassword, "The platform administrator", top, true));
        return out;
    }

    /** Reset link for any account, e.g. an owner who lost access. */
    public ResetLinkResult resetPassword(PlatformReset r) {
        requirePlatform();
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        UUID uid = repository.userByEmail(email).orElseThrow(() -> ApiException.notFound("Account"));
        if (uid.equals(Auth.userId())) throw ApiException.conflict("SELF", "Use 'Change password' for your own account.");
        Accounts.ResetLink link = accounts.createReset(uid, Auth.userId());
        audit.platform(Auth.userId(), "platform.password_reset_link", "user", uid, r.email());
        String note = emails.passwordReset(null, uid, email, link);
        return new ResetLinkResult("/reset-password?token=" + link.token(), link.expiresAt(), note);
    }

    /** Suspension blocks admin access; data is kept and running channels keep playing (MULTITENANCY.md). */
    public Map<String, String> setStatus(UUID id, OrgStatus r) {
        requirePlatform();
        if (!repository.setOrganizationStatus(id, r.status())) throw ApiException.notFound("Organization");
        tokens.bumpOrg(id);                        // members' tokens are re-checked at once
        audit.log(id, Auth.userId(), "platform.org." + r.status().toLowerCase(), "organization", id, "");
        return Map.of("status", r.status());
    }
}
