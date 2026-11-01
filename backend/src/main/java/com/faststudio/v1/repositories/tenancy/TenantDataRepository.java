package com.faststudio.v1.repositories.tenancy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Everything a customer tenant owns, found from the database catalog rather than a hand-kept list, so tables added
 * later are exported and deleted too. A table belongs to the tenant through any of its organization columns
 * (org_id, owner_org_id, partner_org_id) or tenant_id. Table and column names come from pg_catalog and are quoted.
 * Callers run as the system (TenantScope.bypass).
 */
@Repository
public class TenantDataRepository {
    static final List<String> OWNER_COLUMNS = List.of("org_id", "owner_org_id", "partner_org_id", "tenant_id");
    /** Never exported: credentials, hashes and unredacted mail. */
    public static final Set<String> SECRET_COLUMNS = Set.of("tag_url", "token_id", "token_secret", "webhook_secret", "secret_hash",
            "token_hash", "password_hash", "pending_subject", "pending_text", "pending_html");

    private final JdbcClient db;

    public TenantDataRepository(JdbcClient db) { this.db = db; }

    /** A tenant table and the columns that tie its rows to an organization or the tenant. */
    public record Table(String name, List<String> ownerColumns, List<String> columns) {}

    /**
     * A foreign key between two tables: child.column → parent; nullable says whether it can be cleared first.
     * Cascading keys are never cleared (deleting the parent removes the child; a check such as "only SYNC jobs have
     * no asset" may forbid null), so they only order the deletion.
     */
    public record ForeignKey(String child, String column, String parent, boolean nullable) {}

    public List<Table> tables() {
        Map<String, List<String>> owners = new TreeMap<>(), columns = new TreeMap<>();
        db.sql("""
                select c.relname as tbl, a.attname as col from pg_class c join pg_namespace n on n.oid = c.relnamespace
                join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
                where n.nspname = 'public' and c.relkind in ('r','p') and c.relname not in ('organization','customer_tenant','flyway_schema_history')
                order by c.relname, a.attnum""").query((rs, n) -> {
            String t = rs.getString("tbl"), c = rs.getString("col");
            columns.computeIfAbsent(t, k -> new ArrayList<>()).add(c);
            if (OWNER_COLUMNS.contains(c)) owners.computeIfAbsent(t, k -> new ArrayList<>()).add(c);
            return null;
        }).list();
        return owners.entrySet().stream().map(e -> new Table(e.getKey(), e.getValue(), columns.get(e.getKey()))).toList();
    }

    public List<ForeignKey> foreignKeys() {
        return db.sql("""
                select ch.relname as child, a.attname as "column", pa.relname as parent, not a.attnotnull and k.confdeltype <> 'c' as nullable
                from pg_constraint k join pg_class ch on ch.oid = k.conrelid join pg_class pa on pa.oid = k.confrelid
                join pg_namespace n on n.oid = ch.relnamespace
                join pg_attribute a on a.attrelid = k.conrelid and a.attnum = any(k.conkey)
                where k.contype = 'f' and n.nspname = 'public'""").query(ForeignKey.class).list();
    }

    private static String q(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

    private static String owned(Table t) {
        return String.join(" or ", t.ownerColumns().stream()
                .map(c -> c.equals("tenant_id") ? q(c) + " = :tenant" : q(c) + " = any(:orgs)").toList());
    }

    private static Map<String, Object> params(UUID tenant, List<UUID> orgs) {
        return Map.of("tenant", tenant, "orgs", orgs.toArray(new UUID[0]));
    }

    /** The tenant's rows of one table, secret columns left out. */
    public List<Map<String, Object>> rows(Table t, UUID tenant, List<UUID> orgs) {
        String cols = String.join(", ", t.columns().stream().filter(c -> !SECRET_COLUMNS.contains(c)).map(TenantDataRepository::q).toList());
        return db.sql("select " + cols + " from " + q(t.name()) + " where " + owned(t)).params(params(tenant, orgs)).query().listOfRows();
    }

    /** Clears a nullable reference on the tenant's rows (breaks cycles such as channel ⇄ schedule_version before deleting). */
    public void clear(Table t, String column, UUID tenant, List<UUID> orgs) {
        db.sql("update " + q(t.name()) + " set " + q(column) + " = null where " + owned(t)).params(params(tenant, orgs)).update();
    }

    public int delete(Table t, UUID tenant, List<UUID> orgs) {
        return db.sql("delete from " + q(t.name()) + " where " + owned(t)).params(params(tenant, orgs)).update();
    }

    public List<UUID> organizations(UUID tenant) {
        return db.sql("select id from organization where tenant_id = :t").param("t", tenant).query(UUID.class).list();
    }

    public Optional<Map<String, Object>> tenant(UUID tenant) {
        return db.sql("select id, slug, name, status, plan_key, subscription_status from customer_tenant where id = :t")
                .param("t", tenant).query().listOfRows().stream().findFirst();
    }

    public List<Map<String, Object>> organizationRows(UUID tenant) {
        return db.sql("select id, slug, name, kind, status, country, timezone from organization where tenant_id = :t")
                .param("t", tenant).query().listOfRows();
    }

    public List<Map<String, Object>> members(List<UUID> orgs) {
        return db.sql("""
                select m.org_id, u.id as user_id, u.email, u.name, m.role from membership m join app_user u on u.id = m.user_id
                where m.org_id = any(:orgs) order by u.email""").param("orgs", orgs.toArray(new UUID[0])).query().listOfRows();
    }

    /** People who belong only to these organizations (and aren't platform administrators): erased with the tenant. */
    public List<UUID> exclusiveUsers(List<UUID> orgs) {
        return db.sql("""
                select u.id from app_user u where not u.platform_admin
                  and exists (select 1 from membership m where m.user_id = u.id and m.org_id = any(:orgs))
                  and not exists (select 1 from membership m where m.user_id = u.id and m.org_id <> all(:orgs))""")
                .param("orgs", orgs.toArray(new UUID[0])).query(UUID.class).list();
    }

    /** Erases a person's identity but keeps the row, because audit records of other tenants may name them. */
    public void anonymize(List<UUID> users) {
        if (users.isEmpty()) return;
        UUID[] ids = users.toArray(new UUID[0]);
        for (String t : List.of("password_reset", "refresh_token", "personal_access_token"))
            db.sql("delete from " + t + " where user_id = any(:u)").param("u", ids).update();
        db.sql("""
                update app_user set email = 'deleted-' || id || '@deleted.invalid', name = 'Deleted user', password_hash = '!',
                  token_version = token_version + 1 where id = any(:u)""").param("u", ids).update();
    }

    public void deleteOrganizationsAndTenant(UUID tenant) {
        db.sql("delete from organization where tenant_id = :t").param("t", tenant).update();
        db.sql("delete from customer_tenant where id = :t").param("t", tenant).update();
    }
}
