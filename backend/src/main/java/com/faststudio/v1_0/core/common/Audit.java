package com.faststudio.v1_0.core.common;

import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.core.tenant.TenantScope;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Append-only audit trail. Never pass secrets or passwords in detail. Every row names the acting organization:
 * the tenant for company work, the platform organization for platform administration ({@link #platform}).
 * Personal account events (sign-in, password) belong to the person and have no organization.
 */
@Component
public class Audit {
    private final JdbcClient db;

    public Audit(JdbcClient db) {
        this.db = db;
    }

    private volatile UUID platformOrg;

    public UUID platformOrg() {
        if (platformOrg == null) platformOrg = db.sql("select id from organization where kind = 'PLATFORM'").query(UUID.class).single();
        return platformOrg;
    }

    /** Platform administration, recorded as the platform organization (trusted system path: written with RLS bypass). */
    public void platform(UUID actorId, String action, String type, Object id, String detail) {
        TenantScope.bypass(() -> { log(platformOrg(), actorId, action, type, id, detail); return null; });
    }

    /**
     * Something a partner did with a customer's data: recorded in the customer's (owner's) log, naming the partner
     * organization too (via_org_id). Trusted path: PartnerInterceptor has verified the sharing.
     */
    public void partner(UUID ownerOrg, UUID partnerOrg, UUID actorId, String action, String type, Object id, String detail) {
        TenantScope.bypass(() -> db.sql("insert into audit_event(org_id, via_org_id, actor_id, action, resource_type, resource_id, detail) values (:o,:v,:a,:ac,:t,:id,:d)")
                .param("o", ownerOrg).param("v", partnerOrg).param("a", actorId).param("ac", action).param("t", type)
                .param("id", id == null ? null : id.toString()).param("d", detail == null ? "" : detail).update());
    }

    public void log(Tenant t, String action, String type, Object id, String detail) {
        log(t == null ? null : t.orgId(), t == null ? null : t.userId(), action, type, id, detail);
    }

    public void log(UUID orgId, UUID actorId, String action, String type, Object id, String detail) {
        db.sql("insert into audit_event(org_id, actor_id, action, resource_type, resource_id, detail) values (:o,:a,:ac,:t,:id,:d)")
                .param("o", orgId).param("a", actorId).param("ac", action).param("t", type)
                .param("id", id == null ? null : id.toString()).param("d", detail == null ? "" : detail)
                .update();
    }
}
