package com.faststudio.v1.repositories.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the signed-in person's account and memberships (these tables establish the tenant, so they are outside RLS). */
@Repository
public class UserAccountRepository {
    private final JdbcClient db;

    public UserAccountRepository(JdbcClient db) { this.db = db; }


    public record UserRow(UUID id, String name, String email, boolean platformAdmin, boolean mustChangePassword) {}

    public UserRow user(UUID id) {
        return db.sql("select id, name, email, platform_admin, must_change_password from app_user where id = :id").param("id", id)
                .query(UserRow.class).single();
    }

    public record MembershipRow(UUID orgId, String slug, String name, String status, String role, String kind, String features,
                                UUID tenantId, String tenantName) {}

    /** Memberships ordered by tenant then name; a suspended tenant makes all its projects SUSPENDED. */
    public List<MembershipRow> memberships(UUID user) {
        return db.sql("""
                select o.id as org_id, o.slug, o.name, case when t.status = 'SUSPENDED' then 'SUSPENDED' else o.status end as status,
                       m.role, o.kind, o.features, o.tenant_id, t.name as tenant_name
                from membership m join organization o on o.id = m.org_id left join customer_tenant t on t.id = o.tenant_id
                where m.user_id = :id order by t.name, o.name""")
                .param("id", user).query(MembershipRow.class).list();
    }

    public record Credentials(String email, String name, String passwordHash, boolean mustChangePassword) {}

    public Credentials credentials(UUID id) {
        return db.sql("select email, name, password_hash, must_change_password from app_user where id = :id").param("id", id)
                .query(Credentials.class).single();
    }

    public record Named(UUID id, String name) {}

    public Optional<Named> userByEmail(String email) {
        return db.sql("select id, name from app_user where email = :e").param("e", email).query(Named.class).optional();
    }

    /** Self-service reset links created for the account in the last hour (admin-issued links are not counted). */
    public int recentSelfServiceResets(UUID user) {
        return db.sql("select count(*) from password_reset where user_id = :u and created_by is null and created_at > now() - interval '1 hour'")
                .param("u", user).query(Integer.class).single();
    }
}
