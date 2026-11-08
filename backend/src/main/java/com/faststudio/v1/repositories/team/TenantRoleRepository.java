package com.faststudio.v1.repositories.team;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for tenant-wide roles (V30): tenant_membership holds the role; membership rows marked via_tenant carry it into
 * each project of the tenant. A person's own project role (via_tenant = false) is never touched here. Run as the system.
 */
@Repository
public class TenantRoleRepository {
    private final JdbcClient db;

    public TenantRoleRepository(JdbcClient db) { this.db = db; }

    /** projects = how many of the tenant's projects the role reaches (the rest have the person's own role). */
    public record Row(UUID userId, String name, String email, String role, OffsetDateTime createdAt, int projects) {}

    public List<Row> list(UUID tenant) {
        return db.sql("""
                select u.id as user_id, u.name, u.email, t.role, t.created_at,
                  (select count(*) from membership m join organization o on o.id = m.org_id
                   where o.tenant_id = t.tenant_id and m.user_id = t.user_id and m.via_tenant) as projects
                from tenant_membership t join app_user u on u.id = t.user_id where t.tenant_id = :t order by u.name""")
                .param("t", tenant).query(Row.class).list();
    }

    public Optional<String> role(UUID tenant, UUID user) {
        return db.sql("select role from tenant_membership where tenant_id = :t and user_id = :u").param("t", tenant).param("u", user)
                .query(String.class).optional();
    }

    public void save(UUID tenant, UUID user, String role) {
        db.sql("""
                insert into tenant_membership(tenant_id, user_id, role) values (:t, :u, :r)
                on conflict (tenant_id, user_id) do update set role = excluded.role""")
                .param("t", tenant).param("u", user).param("r", role).update();
    }

    public void delete(UUID tenant, UUID user) {
        db.sql("delete from tenant_membership where tenant_id = :t and user_id = :u").param("t", tenant).param("u", user).update();
    }

    /** Writes the role into every project of the tenant, except where the person has their own project role. */
    public void spread(UUID tenant, UUID user, String role) {
        db.sql("""
                insert into membership(org_id, user_id, role, via_tenant)
                select o.id, :u, :r, true from organization o where o.tenant_id = :t
                on conflict (org_id, user_id) do update set role = excluded.role, custom_role_key = null
                where membership.via_tenant""")
                .param("t", tenant).param("u", user).param("r", role).update();
    }

    /** Removes the role from the tenant's projects; the person's own project roles stay. */
    public void withdraw(UUID tenant, UUID user) {
        db.sql("""
                delete from membership m using organization o
                where o.id = m.org_id and o.tenant_id = :t and m.user_id = :u and m.via_tenant""")
                .param("t", tenant).param("u", user).update();
    }

    /** A new project gets every tenant-wide role of its tenant; returns the people who gained access. */
    public List<UUID> spreadToProject(UUID tenant, UUID project) {
        return db.sql("""
                insert into membership(org_id, user_id, role, via_tenant)
                select :p, t.user_id, t.role, true from tenant_membership t where t.tenant_id = :t
                on conflict (org_id, user_id) do nothing returning user_id""")
                .param("t", tenant).param("p", project).query(UUID.class).list();
    }

    /** Projects of the tenant that would be left without a plain owner if this person's tenant-wide OWNER rows went. */
    public List<String> projectsOwnedOnlyThroughTenant(UUID tenant, UUID user) {
        return db.sql("""
                select o.name from organization o join membership m on m.org_id = o.id and m.user_id = :u and m.via_tenant and m.role = 'OWNER'
                where o.tenant_id = :t and not exists (select 1 from membership x where x.org_id = o.id and x.user_id <> :u
                  and x.role = 'OWNER' and x.custom_role_key is null) order by o.name""")
                .param("t", tenant).param("u", user).query(String.class).list();
    }

    public boolean viaTenant(UUID org, UUID user) {
        return db.sql("select coalesce(bool_or(via_tenant), false) from membership where org_id = :o and user_id = :u")
                .param("o", org).param("u", user).query(Boolean.class).single();
    }

    public Optional<UUID> firstProject(UUID tenant) {
        return db.sql("select id from organization where tenant_id = :t order by created_at limit 1").param("t", tenant).query(UUID.class).optional();
    }

    public boolean tenantExists(UUID tenant) {
        return db.sql("select count(*) from customer_tenant where id = :t").param("t", tenant).query(Integer.class).single() > 0;
    }
}
