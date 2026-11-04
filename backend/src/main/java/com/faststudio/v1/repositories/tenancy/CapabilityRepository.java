package com.faststudio.v1.repositories.tenancy;

import com.faststudio.v1.dto.tenancy.CapabilityDto.Capability;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Lookups on the features and permissions catalogue (capability) used by access checks and labels. The catalogue itself
 * is edited through {@link CatalogueRepository}; since V25 every entry is global (org_id null).
 */
@Repository
public class CapabilityRepository {
    private final JdbcClient db;

    public CapabilityRepository(JdbcClient db) { this.db = db; }

    /** Every global entry, for labels. */
    public List<Capability> global() {
        return db.sql("select key, kind, name, description, category, org_id, built_in, enabled from capability where org_id is null order by kind, category, name")
                .query(Capability.class).list();
    }

    /** Enabled keys of one kind that aren't built in: global ones (org null) or, before V25, a project's own. */
    public List<String> enabledCustom(String kind, UUID org, boolean global) {
        return db.sql("select key from capability where kind = :kind and enabled and not built_in and "
                        + (global ? "org_id is null" : "org_id = :o"))
                .param("kind", kind).param("o", org).query(String.class).list();
    }
}
