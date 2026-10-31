package com.faststudio.v1_0.core.tenant;

import com.faststudio.v1_0.core.common.ApiException;

import org.springframework.http.HttpStatus;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated tenant context for one request. Built only by {@link TenantInterceptor}
 * from the signed-in user + a verified membership, never from client-supplied IDs.
 * kind is the organization's kind: CUSTOMER (content owner) or PARTNER (receives shared channels).
 */
public record Tenant(UUID orgId, String slug, String orgName, UUID userId, Role role, String kind, Set<Perm> permissions) {
    public Tenant(UUID orgId,String slug,String orgName,UUID userId,Role role,String kind) {
        this(orgId,slug,orgName,userId,role,kind,Set.copyOf(role.perms));
    }

    public enum Perm { VIEW, ASSETS_EDIT, RIGHTS_EDIT, CHANNELS_EDIT, SCHEDULE_EDIT, SCHEDULE_PUBLISH, ADS_EDIT, DISTRIBUTION_EDIT, ALERTS_EDIT, TEAM_MANAGE, PARTNER_MANAGE }

    public enum Role {
        OWNER(4, EnumSet.complementOf(EnumSet.of(Perm.PARTNER_MANAGE))),
        ADMIN(3, EnumSet.complementOf(EnumSet.of(Perm.PARTNER_MANAGE))),
        CONTENT_MANAGER(1, EnumSet.of(Perm.VIEW, Perm.ASSETS_EDIT, Perm.RIGHTS_EDIT)),
        CHANNEL_OPERATOR(1, EnumSet.of(Perm.VIEW, Perm.CHANNELS_EDIT, Perm.SCHEDULE_EDIT, Perm.SCHEDULE_PUBLISH, Perm.DISTRIBUTION_EDIT, Perm.ALERTS_EDIT)),
        AD_OPS(1, EnumSet.of(Perm.VIEW, Perm.ADS_EDIT, Perm.ALERTS_EDIT)),
        ANALYST(0, EnumSet.of(Perm.VIEW)),
        // partner organizations: they only read what customers share with them (docs/PARTNER_ACCESS_AND_TOKENS.md §3)
        PARTNER_ADMIN(4, EnumSet.of(Perm.VIEW, Perm.TEAM_MANAGE, Perm.PARTNER_MANAGE)),
        PARTNER_VIEWER(0, EnumSet.of(Perm.VIEW));

        public final int rank;
        public final Set<Perm> perms;

        Role(int rank, Set<Perm> perms) {
            this.rank = rank;
            this.perms = perms;
        }

        public boolean partner() { return this == PARTNER_ADMIN || this == PARTNER_VIEWER; }

        /** The role every organization of this kind must keep at least one of. */
        public static Role top(String kind) { return "PARTNER".equals(kind) ? PARTNER_ADMIN : OWNER; }
    }

    public boolean partnerOrg() {
        return "PARTNER".equals(kind);
    }

    public boolean can(Perm p) {
        return permissions.contains(p);
    }

    public void require(Perm p) {
        if (!can(p)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Your role (" + role + ") is not allowed to do this. Ask an organization admin.");
    }
}
