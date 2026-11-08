package com.faststudio.v1.services.team;

import com.faststudio.v1.core.auth.Accounts;
import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.team.PeopleDto.*;
import com.faststudio.v1.repositories.team.MembershipRepository;
import com.faststudio.v1.repositories.team.PeopleRepository;
import com.faststudio.v1.services.tenancy.AccessRoleServiceV1;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * People across every organization, for platform administrators (P2): find anyone, add them to an organization with a
 * built-in or custom role, change or remove a membership, and switch an account off or on. Same rules as the Team
 * screen where they protect an organization: roles must fit the organization's kind, and it keeps one plain owner.
 * Changes apply to the person's tokens at once.
 */
@Service
public class PlatformPeopleServiceV1 {
    private final PeopleRepository people;
    private final MembershipRepository members;
    private final AccessRoleServiceV1 accessRoles;
    private final AccountEmailService emails;
    private final PasswordEncoder encoder;
    private final Tokens tokens;
    private final Audit audit;
    private final TransactionTemplate tx;
    private final TenantRoleServiceV1 tenantRoles;

    public PlatformPeopleServiceV1(PeopleRepository people, MembershipRepository members, AccessRoleServiceV1 accessRoles, AccountEmailService emails,
                                   PasswordEncoder encoder, Tokens tokens, Audit audit, TransactionTemplate tx, TenantRoleServiceV1 tenantRoles) {
        this.tenantRoles = tenantRoles;
        this.people = people;
        this.members = members;
        this.accessRoles = accessRoles;
        this.emails = emails;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
        this.tx = tx;
    }

    public Page list(String q, UUID tenant, int limit, int offset) {
        int[] total = {0};
        List<Person> page = system(() -> people.page(q == null ? "" : q.trim(), tenant, Math.min(Math.max(limit, 1), 200), Math.max(offset, 0), total));
        return new Page(page, total[0]);
    }

    /** Custom roles that can be given in this organization. */
    public List<CustomRole> customRoles(UUID org) {
        return system(() -> {
            String kind = people.joinableKind(org).orElseThrow(() -> ApiException.notFound("Organization"));
            return accessRoles.platformAssignableRoles(org, kind).stream().map(r -> new CustomRole(r.key(), r.name(), r.baseRole())).toList();
        });
    }

    public Person get(UUID id) { return system(() -> people.person(id)).orElseThrow(() -> ApiException.notFound("Person")); }

    public AddResult add(AddPerson r) {
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        String[] temporary = {null};
        UUID uid = system(() -> tx.execute(s -> {
            String kind = people.joinableKind(r.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
            Tenant.Role role = role(r.orgId(), kind, r.role(), r.customRoleKey());
            UUID id = members.userByEmail(email).orElse(null);
            if (id == null) {
                temporary[0] = Accounts.temporaryPassword();
                id = members.createUser(email, r.name().trim(), encoder.encode(temporary[0]));
            }
            if (members.isMember(r.orgId(), id)) throw ApiException.conflict("ALREADY_MEMBER", email + " is already a member there.");
            members.addMember(r.orgId(), id, role.name(), blank(r.customRoleKey()));
            tokens.bump(List.of(id));
            audit.log(r.orgId(), Auth.userId(), "platform.member.add", "user", id, email + " as " + role);
            return id;
        }));
        Tenant.Role role = Tenant.Role.valueOf(r.role());
        String note = system(() -> emails.welcome(r.orgId(), uid, email, temporary[0], "The platform administrator", role, false));
        return new AddResult(get(uid), temporary[0], note);
    }

    /** Changes a person's role in one organization. */
    public Person setMembership(UUID user, UUID org, MembershipRequest r) {
        system(() -> tx.execute(s -> {
            String kind = people.joinableKind(org).orElseThrow(() -> ApiException.notFound("Organization"));
            Tenant.Role target = role(org, kind, r.role(), r.customRoleKey());
            Tenant.Role current = members.role(org, user).map(Tenant.Role::valueOf).orElseThrow(() -> ApiException.notFound("Membership"));
            tenantRoles.requireProjectManaged(org, user);
            if (current == Tenant.Role.top(kind) && (target != current || blank(r.customRoleKey()) != null)) lastOwnerGuard(org, kind);
            members.changeRole(org, user, target.name(), blank(r.customRoleKey()));
            tokens.bump(List.of(user));
            audit.log(org, Auth.userId(), "platform.member.role", "user", user, current + " → " + target);
            return null;
        }));
        return get(user);
    }

    public Person removeMembership(UUID user, UUID org) {
        system(() -> tx.execute(s -> {
            String kind = people.joinableKind(org).orElseThrow(() -> ApiException.notFound("Organization"));
            Tenant.Role current = members.role(org, user).map(Tenant.Role::valueOf).orElseThrow(() -> ApiException.notFound("Membership"));
            tenantRoles.requireProjectManaged(org, user);
            if (current == Tenant.Role.top(kind)) lastOwnerGuard(org, kind);
            members.removeMember(org, user);
            tokens.bump(List.of(user));
            audit.log(org, Auth.userId(), "platform.member.remove", "user", user, current.name());
            return null;
        }));
        return get(user);
    }

    /** Switches an account off (sign-in, refresh and every token stop at once) or back on. Memberships are kept. */
    public Person setStatus(UUID user, StatusRequest r) {
        if (user.equals(Auth.userId())) throw ApiException.conflict("SELF", "You can't switch off your own account.");
        system(() -> tx.execute(s -> {
            if (!people.setDisabled(user, r.disabled())) throw ApiException.notFound("Person");
            if (r.disabled()) people.revokeSignIns(user);
            tokens.bump(List.of(user));
            audit.platform(Auth.userId(), r.disabled() ? "platform.user.disable" : "platform.user.enable", "user", user, "");
            return null;
        }));
        return get(user);
    }

    /** A built-in role of this kind of organization, optionally narrowed by one of its enabled custom roles. */
    private Tenant.Role role(UUID org, String kind, String name, String custom) {
        Tenant.Role role;
        try { role = Tenant.Role.valueOf(name); } catch (Exception e) { throw ApiException.bad("Unknown role " + name); }
        if (role.partner() != kind.equals("PARTNER")) throw ApiException.bad(AccountEmailService.label(role) + " is not a role in this kind of organization.");
        if (blank(custom) != null && !accessRoles.platformAssignable(org, kind, custom).baseRole().equals(role.name()))
            throw ApiException.bad("The role ceiling does not match the selected role.");
        return role;
    }

    private void lastOwnerGuard(UUID org, String kind) {
        Tenant.Role top = Tenant.Role.top(kind);
        members.lockOrganization(org);
        if (members.plainMembersWithRole(org, top.name()) <= 1)
            throw ApiException.conflict("LAST_OWNER", "An organization must keep at least one " + AccountEmailService.label(top).toLowerCase(Locale.ROOT) + ".");
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static <T> T system(Supplier<T> work) { return TenantScope.bypass(work); }
}
