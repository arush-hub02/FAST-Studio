package com.faststudio.v1.repositories.tenancy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for custom access roles (access_role) and the custom role a member holds (membership.custom_role_key). A role is
 * global (org_id null; platform) or one project's own (V25); row-level security hides other projects' roles.
 */
@Repository
public class AccessRoleRepository {
    private final JdbcClient db;

    public AccessRoleRepository(JdbcClient db) { this.db = db; }

    /** Stored custom role; {@code permissions} is comma-separated; orgId null = global. */
    public record Row(String key, String name, String kind, String baseRole, String permissions, boolean enabled, int revision, UUID orgId) {}

    private static final String COLUMNS = "key, name, kind, base_role, permissions, enabled, revision, org_id";

    /** Global roles (platform administration). */
    public List<Row> globalRoles() {
        return db.sql("select " + COLUMNS + " from access_role where org_id is null order by kind, name").query(Row.class).list();
    }

    /** Roles usable in a project: the global ones and the project's own. */
    public List<Row> rolesFor(UUID org) {
        return db.sql("select " + COLUMNS + " from access_role where org_id is null or org_id = :o order by org_id nulls first, kind, name")
                .param("o", org).query(Row.class).list();
    }

    /** Permissions (comma-separated) of the member's custom role, only when it is enabled and matches the organization type and ceiling. */
    public Optional<String> customPermissions(UUID org, UUID user) {
        return db.sql("""
                select r.permissions from membership m join access_role r on r.key = m.custom_role_key
                join organization o on o.id = m.org_id
                where m.org_id = :o and m.user_id = :u and r.kind = o.kind and r.base_role = m.role and r.enabled
                  and (r.org_id is null or r.org_id = m.org_id)""")
                .param("o", org).param("u", user).query(String.class).optional();
    }

    /** True when the member has a custom role key at all (valid or not). */
    public boolean hasCustomRole(UUID org, UUID user) {
        return db.sql("select count(*) from membership where org_id = :o and user_id = :u and custom_role_key is not null")
                .param("o", org).param("u", user).query(Integer.class).single() > 0;
    }

    /** True when any role, global or of any project, already uses the key (keys are unique). */
    public boolean exists(String key) {
        return db.sql("select count(*) from access_role where key = :k /* all tenants: keys are unique platform-wide */")
                .param("k", key).query(Integer.class).single() > 0;
    }

    public void insert(String key, String name, String kind, String baseRole, String permissions, boolean enabled, UUID org) {
        Map<String, Object> p = new HashMap<>(Map.of("k", key, "n", name, "kind", kind, "b", baseRole, "p", permissions, "e", enabled));
        p.put("o", org);
        db.sql("insert into access_role(key, name, kind, base_role, permissions, enabled, org_id) values (:k, :n, :kind, :b, :p, :e, :o)").params(p).update();
    }

    /** Optimistic update of a role owned by {@code org} (null = global); false when it changed since expectedRevision. */
    public boolean update(String key, String name, String permissions, boolean enabled, int expectedRevision, UUID org) {
        Map<String, Object> p = new HashMap<>(Map.of("n", name, "p", permissions, "e", enabled, "k", key, "v", expectedRevision));
        p.put("o", org);
        return db.sql("""
                update access_role set name = :n, permissions = :p, enabled = :e, revision = revision + 1
                where key = :k and revision = :v and org_id is not distinct from cast(:o as uuid)""").params(p).update() > 0;
    }

    /** Deletes a project's own role. */
    public int delete(String key, UUID org) {
        return db.sql("delete from access_role where key = :k and org_id = :o").param("k", key).param("o", org).update();
    }

    public List<UUID> holders(String key) {
        return db.sql("select distinct user_id from membership where custom_role_key = :k").param("k", key).query(UUID.class).list();
    }
}
