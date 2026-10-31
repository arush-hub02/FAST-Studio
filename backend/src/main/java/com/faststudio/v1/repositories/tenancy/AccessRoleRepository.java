package com.faststudio.v1.repositories.tenancy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for custom access roles (access_role) and the custom role a member holds (membership.custom_role_key). */
@Repository
public class AccessRoleRepository {
    private final JdbcClient db;

    public AccessRoleRepository(JdbcClient db) { this.db = db; }

    /** Stored custom role; {@code permissions} is comma-separated. */
    public record Row(String key, String name, String kind, String baseRole, String permissions, boolean enabled, int revision) {}

    public List<Row> roles() { return db.sql("select * from access_role order by kind, name").query(Row.class).list(); }

    /** Permissions (comma-separated) of the member's custom role, only when it is enabled and matches the organization type and ceiling. */
    public Optional<String> customPermissions(UUID org, UUID user) {
        return db.sql("""
                select r.permissions from membership m join access_role r on r.key = m.custom_role_key
                join organization o on o.id = m.org_id
                where m.org_id = :o and m.user_id = :u and r.kind = o.kind and r.base_role = m.role and r.enabled""")
                .param("o", org).param("u", user).query(String.class).optional();
    }

    /** True when the member has a custom role key at all (valid or not). */
    public boolean hasCustomRole(UUID org, UUID user) {
        return db.sql("select count(*) from membership where org_id = :o and user_id = :u and custom_role_key is not null")
                .param("o", org).param("u", user).query(Integer.class).single() > 0;
    }

    public void insert(String key, String name, String kind, String baseRole, String permissions, boolean enabled) {
        db.sql("insert into access_role(key, name, kind, base_role, permissions, enabled) values (:k, :n, :kind, :b, :p, :e)")
                .param("k", key).param("n", name).param("kind", kind).param("b", baseRole).param("p", permissions).param("e", enabled).update();
    }

    /** Optimistic update; false when the role changed since expectedRevision. */
    public boolean update(String key, String name, String permissions, boolean enabled, int expectedRevision) {
        return db.sql("update access_role set name = :n, permissions = :p, enabled = :e, revision = revision + 1 where key = :k and revision = :v")
                .param("n", name).param("p", permissions).param("e", enabled).param("k", key).param("v", expectedRevision).update() > 0;
    }

    public List<UUID> holders(String key) {
        return db.sql("select distinct user_id from membership where custom_role_key = :k").param("k", key).query(UUID.class).list();
    }
}
