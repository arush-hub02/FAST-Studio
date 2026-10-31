package com.faststudio.v1.core.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant the current thread works for. {@link TenantAwareDataSource} stamps it onto every database
 * connection, and PostgreSQL row-level security (V2 migration) only shows rows of that tenant.
 * Bypass is for trusted system paths only: platform admin, background jobs, demo seeding, session lookup.
 */
public final class TenantScope {
    private TenantScope() {}

    private static final ThreadLocal<UUID> ORG = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

    public static UUID org() { return ORG.get(); }

    public static boolean bypassing() { return Boolean.TRUE.equals(BYPASS.get()); }

    /** Request-scoped; cleared by {@link TenantScopeFilter}. */
    public static void set(UUID org) { ORG.set(org); }

    public static void setBypass(boolean on) { BYPASS.set(on); }

    public static void clear() {
        ORG.remove();
        BYPASS.remove();
    }

    public static <T> T as(UUID org, Supplier<T> work) {
        UUID prevOrg = ORG.get();
        Boolean prevBypass = BYPASS.get();
        ORG.set(org);
        BYPASS.remove();
        try {
            return work.get();
        } finally {
            restore(prevOrg, prevBypass);
        }
    }

    public static <T> T bypass(Supplier<T> work) {
        UUID prevOrg = ORG.get();
        Boolean prevBypass = BYPASS.get();
        BYPASS.set(true);
        try {
            return work.get();
        } finally {
            restore(prevOrg, prevBypass);
        }
    }

    private static void restore(UUID org, Boolean bypass) {
        if (org == null) ORG.remove(); else ORG.set(org);
        if (bypass == null) BYPASS.remove(); else BYPASS.set(bypass);
    }
}
