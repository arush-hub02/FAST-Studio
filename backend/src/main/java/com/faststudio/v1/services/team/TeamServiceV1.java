package com.faststudio.v1.services.team;

import com.faststudio.v1.core.auth.Accounts;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.team.TeamDto.*;
import com.faststudio.v1.repositories.team.MembershipRepository;
import com.faststudio.v1.services.tenancy.AccessRoleServiceV1;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Members of one organization (customer project or partner organization).
 *
 * <p>Rules: nobody grants or changes a role above their own; only owners (partner admins) manage owners
 * (partner admins); an organization always keeps one plain owner; a password reset by an org admin is only
 * allowed for people who belong to this organization alone, because a password unlocks all their organizations.
 */
@Service
public class TeamServiceV1 {
    private final MembershipRepository repository;
    private final AccountEmailService emails;
    private final AccessRoleServiceV1 accessRoles;
    private final PasswordEncoder encoder;
    private final Accounts accounts;
    private final Tokens tokens;
    private final Audit audit;

    public TeamServiceV1(MembershipRepository repository, AccountEmailService emails, AccessRoleServiceV1 accessRoles,
                           PasswordEncoder encoder, Accounts accounts, Tokens tokens, Audit audit) {
        this.repository = repository;
        this.emails = emails;
        this.accessRoles = accessRoles;
        this.encoder = encoder;
        this.accounts = accounts;
        this.tokens = tokens;
        this.audit = audit;
    }

    public List<Member> members(Tenant t) { return repository.members(t.orgId()); }

    /** Adds a person (creating the account if needed) with a built-in or custom role. */
    @Transactional
    public InviteResult invite(Tenant t, InviteRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Tenant.Role role = grant(t, r.role(), r.customRoleKey());
        ceiling(t, role);
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        String temporaryPassword = null;
        UUID uid = repository.userByEmail(email).orElse(null);
        if (uid == null) {
            temporaryPassword = Accounts.temporaryPassword();
            uid = repository.createUser(email, r.name().trim(), encoder.encode(temporaryPassword));
        }
        if (repository.isMember(t.orgId(), uid)) throw ApiException.conflict("ALREADY_MEMBER", email + " is already a member.");
        repository.addMember(t.orgId(), uid, role.name(), r.customRoleKey());
        tokens.bump(List.of(uid));                 // their sign-in token gains this organization on its next refresh
        audit.log(t, "member.invite", "user", uid, email + " as " + role);
        Member member = members(t).stream().filter(x -> x.email().equals(email)).findFirst().orElseThrow();
        String note = emails.welcome(t.orgId(), uid, email, temporaryPassword, repository.userName(t.userId()), role, false);
        return new InviteResult(member, temporaryPassword, note);
    }

    /** Single-use reset link that expires in 24 h (see class comment for who may be reset). */
    public ResetLinkResult resetPassword(Tenant t, UUID userId) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        if (userId.equals(t.userId())) throw ApiException.conflict("SELF", "Use 'Change password' for your own account.");
        ceiling(t, currentRole(t, userId));
        if (repository.membershipsElsewhere(t.orgId(), userId) > 0 || repository.isPlatformAdmin(userId))
            throw new ApiException(HttpStatus.FORBIDDEN, "RESET_ELSEWHERE",
                    "This person also has access outside this organization, so only the platform administrator can reset their password.");
        Accounts.ResetLink link = accounts.createReset(userId, t.userId());
        audit.log(t, "member.password_reset_link", "user", userId, "expires " + link.expiresAt());
        String email = repository.userEmail(userId);
        String note = emails.passwordReset(t.orgId(), userId, email, link);
        return new ResetLinkResult("/reset-password?token=" + link.token(), link.expiresAt(), note);
    }

    @Transactional
    public Map<String, String> changeRole(Tenant t, UUID userId, RoleRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Tenant.Role target = grant(t, r.role(), r.customRoleKey());
        Tenant.Role current = currentRole(t, userId);
        ceiling(t, target);
        ceiling(t, current);
        // Moving the last plain owner to another (or a custom) role would leave nobody able to recover the organization.
        if (current == Tenant.Role.top(t.kind()) && (target != current || r.customRoleKey() != null)) lastOwnerGuard(t);
        repository.changeRole(t.orgId(), userId, target.name(), r.customRoleKey());
        tokens.bump(List.of(userId));
        audit.log(t, "member.role", "user", userId, current + " → " + target);
        return Map.of("role", target.name());
    }

    @Transactional
    public void remove(Tenant t, UUID userId) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        if (userId.equals(t.userId())) throw ApiException.conflict("SELF", "You can't remove yourself.");
        Tenant.Role current = currentRole(t, userId);
        ceiling(t, current);
        if (current == Tenant.Role.top(t.kind())) lastOwnerGuard(t);
        repository.removeMember(t.orgId(), userId);
        tokens.bump(List.of(userId));
        audit.log(t, "member.remove", "user", userId, current.name());
    }

    // ------------------------------------------------------------------ rules

    private Tenant.Role currentRole(Tenant t, UUID userId) {
        return repository.role(t.orgId(), userId).map(Tenant.Role::valueOf).orElseThrow(() -> ApiException.notFound("Member"));
    }

    /** Nobody can grant or change a role above their own; only owners (partner admins) manage owners (partner admins). */
    private static void ceiling(Tenant t, Tenant.Role role) {
        if (role.rank > t.role().rank || (role == Tenant.Role.top(t.kind()) && t.role() != role))
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CEILING", "You can't grant or change a role higher than your own.");
    }

    private void lastOwnerGuard(Tenant t) {
        Tenant.Role top = Tenant.Role.top(t.kind());
        repository.lockOrganization(t.orgId());
        if (repository.plainMembersWithRole(t.orgId(), top.name()) <= 1)
            throw ApiException.conflict("LAST_OWNER", "An organization must keep at least one " + AccountEmailService.label(top).toLowerCase(Locale.ROOT) + ".");
    }

    /** Partner roles exist only in partner organizations, customer roles only in customer organizations. */
    private static Tenant.Role role(Tenant t, String r) {
        Tenant.Role role;
        try { role = Tenant.Role.valueOf(r); } catch (Exception e) { throw ApiException.bad("Unknown role " + r); }
        if (role.partner() != t.partnerOrg()) throw ApiException.bad(AccountEmailService.label(role) + " is not a role in this kind of organization.");
        return role;
    }

    /** Resolves the requested built-in role, validating the optional custom role and preventing privilege escalation. */
    private Tenant.Role grant(Tenant t, String role, String custom) {
        if (custom != null) {
            var definition = accessRoles.assignable(t, custom);
            if (!definition.baseRole().equals(role)) throw ApiException.bad("The role ceiling does not match the selected role.");
            return role(t, role);
        }
        var target = role(t, role);
        if (!t.permissions().containsAll(target.perms))
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CEILING", "You cannot grant permissions you do not hold.");
        return target;
    }
}
