package com.faststudio.v1.core.auth;

import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.repositories.tenancy.AccessRoleRepository;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Effective permissions of a member: the built-in role's permissions, narrowed by a custom role when one is assigned.
 * Used by the request interceptors, token minting and the "me" endpoint.
 */
@Component
public class Permissions {
    private final AccessRoleRepository roles;

    public Permissions(AccessRoleRepository roles) { this.roles = roles; }

    /** What a request may do: the role to act as and its permissions. */
    public record Effective(Tenant.Role role, Set<Tenant.Perm> perms) {}

    /**
     * A personal access token never does more than the role ceiling chosen when it was made, nor more than its owner may
     * do now: the permissions are the intersection, and the role is the lower of the two (the ceiling when ranks tie).
     */
    public Effective forPersonalToken(UUID org, UUID user, Tenant.Role member, Tenant.Role ceiling) {
        Set<Tenant.Perm> perms = EnumSet.noneOf(Tenant.Perm.class);
        perms.addAll(of(org, user, member));
        perms.retainAll(ceiling.perms);
        return new Effective(ceiling.rank <= member.rank ? ceiling : member, Set.copyOf(perms));
    }

    /** A disabled or mismatched custom role falls back to VIEW only (fail closed), never to the full ceiling. */
    public Set<Tenant.Perm> of(UUID org, UUID user, Tenant.Role base) {
        return roles.customPermissions(org, user)
                .map(csv -> {
                    Set<Tenant.Perm> granted = new HashSet<>();
                    for (Tenant.Perm p : base.perms) if (Times.codes(csv).contains(p.name())) granted.add(p);
                    return Set.copyOf(granted);
                })
                .orElseGet(() -> roles.hasCustomRole(org, user) ? Set.of(Tenant.Perm.VIEW) : Set.copyOf(base.perms));
    }
}
