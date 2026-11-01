package com.faststudio.v1.repositories.tenancy;

import com.faststudio.v1.dto.tenancy.CapabilityDto.Capability;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the features and permissions catalogue (capability, V24). Global rows (org_id null) are written only on the
 * system login (platform administration); a project's own rows carry its organization and follow row-level security.
 */
@Repository
public class CapabilityRepository {
    private static final String COLUMNS = "key, kind, name, description, category, org_id, built_in, enabled";
    private final JdbcClient db;

    public CapabilityRepository(JdbcClient db) { this.db = db; }

    /** The global catalogue (platform administration). */
    public List<Capability> global() {
        return db.sql("select " + COLUMNS + " from capability where org_id is null order by kind, category, name").query(Capability.class).list();
    }

    /** Everything a project can see: the global catalogue plus its own rows. */
    public List<Capability> visibleTo(UUID org) {
        return db.sql("select " + COLUMNS + " from capability where org_id is null or org_id = :o order by kind, org_id nulls first, category, name")
                .param("o", org).query(Capability.class).list();
    }

    public Optional<Capability> find(String key) {
        return db.sql("select " + COLUMNS + " from capability where key = :k /* all tenants: keys are unique; callers check the owner (RLS hides other projects' rows) */")
                .param("k", key).query(Capability.class).optional();
    }

    /** Inserts a row; false when the key is already taken anywhere (keys are unique across the platform). */
    public boolean insert(Capability c) {
        return db.sql("""
                insert into capability(key, kind, name, description, category, org_id, enabled) values (:k, :kind, :n, :d, :c, :o, :e)
                on conflict (key) do nothing""").params(params(c)).update() > 0;
    }

    /** Updates name, description, category and the switch of a row owned by {@code org} (null = global). */
    public boolean update(Capability c) {
        return db.sql("""
                update capability set name = :n, description = :d, category = :c, enabled = :e
                where key = :k and kind = :kind and org_id is not distinct from cast(:o as uuid)""").params(params(c)).update() > 0;
    }

    /** Deletes a non-built-in row owned by {@code org} (null = global). */
    public boolean delete(String key, UUID org) {
        return db.sql("delete from capability where key = :k and not built_in and org_id is not distinct from cast(:o as uuid)")
                .param("k", key).param("o", org).update() > 0;
    }

    /** Enabled keys of one kind that aren't built in: global ones (org null) or the project's own. */
    public List<String> enabledCustom(String kind, UUID org, boolean global) {
        return db.sql("select key from capability where kind = :kind and enabled and not built_in and "
                        + (global ? "org_id is null" : "org_id = :o"))
                .param("kind", kind).param("o", org).query(String.class).list();
    }

    private static Map<String, Object> params(Capability c) {
        Map<String, Object> p = new java.util.HashMap<>(Map.of("k", c.key(), "kind", c.kind(), "n", c.name(), "d", c.description(),
                "c", c.category(), "e", c.enabled()));
        p.put("o", c.orgId());
        return p;
    }
}
