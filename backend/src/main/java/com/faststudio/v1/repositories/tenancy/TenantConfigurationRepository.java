package com.faststudio.v1.repositories.tenancy;

import com.faststudio.v1.dto.tenancy.TenantConfiguration.*;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.tenant.Tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for tenants, plans, connector grants and property values.
 *
 * <p>Project-scoped tables are protected by PostgreSQL row-level security. Callers that need to
 * read across a tenant's projects (the settings screens) wrap calls in {@code TenantScope.bypass}
 * only after checking access in the service layer.
 */
@Repository
public class TenantConfigurationRepository {
    private final JdbcClient db;

    public TenantConfigurationRepository(JdbcClient db) { this.db = db; }


    // ------------------------------------------------------------------ tenants and projects

    public List<Account> accounts() {
        return db.sql("""
                select t.id, t.slug, t.name, t.status, t.plan_key, t.subscription_status, t.revision,
                       (select count(*) from organization o where o.tenant_id = t.id) as projects,
                       fs_customer_channel_count(t.id) as channels
                from customer_tenant t order by t.name""")
                .query(Account.class).list();
    }

    public Account account(UUID id) {
        return db.sql("""
                select id, slug, name, status, plan_key, subscription_status, revision,
                       (select count(*) from organization where tenant_id = :t) as projects,
                       fs_customer_channel_count(:t) as channels
                from customer_tenant where id = :t""")
                .param("t", id).query(Account.class).optional().orElseThrow(() -> ApiException.notFound("Tenant"));
    }

    /** Takes a row lock on the tenant so limit checks and plan changes cannot race. */
    public void lockTenant(UUID tenant) {
        db.sql("select id from customer_tenant where id = :t for update").param("t", tenant)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Tenant"));
    }

    public List<Project> projects(UUID tenant) {
        return db.sql("""
                select o.id, o.tenant_id, o.slug, o.name, o.status,
                       (select count(*) from membership m where m.org_id = o.id) as members
                from organization o where o.tenant_id = :t and o.kind = 'CUSTOMER' order by o.name""")
                .param("t", tenant).query(Project.class).list();
    }

    /** The tenant that owns a project. Partner/platform organizations have none and are reported as not found. */
    public UUID tenant(UUID project) {
        return db.sql("select tenant_id from organization where id = :p and kind = 'CUSTOMER'").param("p", project)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Project"));
    }

    /** People who are owner/admin in at least one project of the tenant (candidates for a new project's owner). */
    public List<Owner> owners(UUID tenant) {
        return db.sql("""
                select distinct u.id, u.name, u.email from app_user u
                join membership m on m.user_id = u.id join organization o on o.id = m.org_id
                where o.tenant_id = :t and m.role in ('OWNER','ADMIN') order by u.name""")
                .param("t", tenant).query(Owner.class).list();
    }

    // ------------------------------------------------------------------ global definitions

    public record PlanRow(String key, String name, boolean enabled, int maxProjects, int maxChannels,
                          BigDecimal monthlyPrice, String currency, String features) {}

    public List<PlanRow> plans() { return db.sql("select * from subscription_plan order by name").query(PlanRow.class).list(); }

    public List<Connector> connectors() {
        return db.sql("select * from connector_definition order by category, name").query(Connector.class).list();
    }

    /** Raw property definition; {@code options} and {@code defaultValue} are JSON text (default may be sealed if secret). */
    public record Definition(String key, String label, String valueType, String connectorKey, boolean secret,
                             boolean projectOverride, boolean enabled, String options, String defaultValue, int revision) {}

    public List<Definition> definitions() {
        return db.sql("select * from property_definition order by connector_key nulls first, label").query(Definition.class).list();
    }

    public Definition definition(String key) {
        return db.sql("select * from property_definition where key = :k").param("k", key)
                .query(Definition.class).optional().orElseThrow(() -> ApiException.notFound("Property"));
    }

    // ------------------------------------------------------------------ stored values (project == null means the tenant scope)

    public record Stored(String value) {}

    public String value(UUID tenant, UUID project, String key) {
        var q = project == null
                ? db.sql("select value from tenant_property_value where tenant_id = :id and property_key = :k").param("id", tenant)
                : db.sql("select value from project_property_value where org_id = :id and property_key = :k").param("id", project);
        return q.param("k", key).query(Stored.class).optional().map(Stored::value).orElse(null);
    }

    /** Upserts a value; {@code null} deletes it so the scope inherits again. */
    public void value(UUID tenant, UUID project, String key, String value) {
        String table = project == null ? "tenant_property_value" : "project_property_value";
        String column = project == null ? "tenant_id" : "org_id";
        UUID id = project == null ? tenant : project;
        if (value == null) {
            db.sql("delete from " + table + " where " + column + " = :id and property_key = :k").param("id", id).param("k", key).update();
        } else {
            db.sql("insert into " + table + "(" + column + ", property_key, value) values (:id, :k, :v) "
                    + "on conflict (" + column + ", property_key) do update set value = excluded.value")
                    .param("id", id).param("k", key).param("v", value).update();
        }
    }

    /** All stored values of one property, across every tenant and project (used to revalidate on definition changes). */
    public List<String> allValues(String key) {
        return db.sql("select value from tenant_property_value where property_key = :k "
                        + "union all select value from project_property_value where property_key = :k")
                .param("k", key).query(String.class).list();
    }

    // ------------------------------------------------------------------ connector grants (project == null means the tenant scope)

    /** TRUE/FALSE when the scope has an explicit setting, null when it inherits. */
    public Boolean enabled(UUID tenant, UUID project, String key) {
        var q = project == null
                ? db.sql("select enabled from tenant_connector where tenant_id = :id and connector_key = :k").param("id", tenant)
                : db.sql("select enabled from project_connector where org_id = :id and connector_key = :k").param("id", project);
        return q.param("k", key).query(Boolean.class).optional().orElse(null);
    }

    /** Upserts a grant; {@code null} deletes it so the scope inherits again. */
    public void enable(UUID tenant, UUID project, String key, Boolean enabled) {
        String table = project == null ? "tenant_connector" : "project_connector";
        String column = project == null ? "tenant_id" : "org_id";
        UUID id = project == null ? tenant : project;
        if (enabled == null) {
            db.sql("delete from " + table + " where " + column + " = :id and connector_key = :k").param("id", id).param("k", key).update();
        } else {
            db.sql("insert into " + table + "(" + column + ", connector_key, enabled) values (:id, :k, :e) "
                    + "on conflict (" + column + ", connector_key) do update set enabled = excluded.enabled")
                    .param("id", id).param("k", key).param("e", enabled).update();
        }
    }

    /** True when the project may use a connector with this driver: globally enabled, granted to its tenant, not opted out by the project. */
    public boolean driverEnabled(UUID project, String driver) {
        return db.sql("""
                select count(*) from organization o join customer_tenant t on t.id = o.tenant_id
                join tenant_connector tc on tc.tenant_id = t.id and tc.enabled
                join connector_definition c on c.key = tc.connector_key and c.enabled and c.driver = :d
                left join project_connector pc on pc.org_id = o.id and pc.connector_key = c.key
                where o.id = :p and coalesce(pc.enabled, true)""")
                .param("p", project).param("d", driver).query(Integer.class).single() > 0;
    }

    // --- access checks ---

    public boolean platformAdmin(UUID user) {
        return db.sql("select platform_admin from app_user where id = :u").param("u", user).query(Boolean.class).single();
    }

    /**
     * An active project of the active tenant where the user is a member (with one of roles, when given), preferring
     * {@code preferred} so audit rows land in the caller's current row-level-security scope.
     */
    public Optional<UUID> memberProject(UUID tenant, UUID project, UUID user, UUID preferred, List<String> roles) {
        var q = db.sql("""
                select o.id from organization o join membership m on m.org_id = o.id
                join customer_tenant t on t.id = o.tenant_id
                where o.tenant_id = :t and m.user_id = :u and o.status = 'ACTIVE' and t.status = 'ACTIVE'"""
                        + (project == null ? "" : " and o.id = :p") + (roles.isEmpty() ? "" : " and m.role in (:roles)")
                        + " order by (o.id = cast(:current as uuid)) desc nulls last, o.id limit 1")
                .param("t", tenant).param("u", user).param("current", preferred);
        if (project != null) q = q.param("p", project);
        if (!roles.isEmpty()) q = q.param("roles", roles);
        return q.query(UUID.class).optional();
    }

    /** Member of the project, optionally only with one of roles (empty = any role). */
    public boolean isMember(UUID project, UUID user, List<String> roles) {
        return db.sql("select count(*) from membership where org_id = :p and user_id = :u" + (roles.isEmpty() ? "" : " and role in (:roles)"))
                .param("p", project).param("u", user).params(roles.isEmpty() ? Map.of() : Map.of("roles", roles)).query(Integer.class).single() > 0;
    }

    /** Owner of at least one project of the tenant. */
    public boolean isTenantOwner(UUID tenant, UUID user) {
        return db.sql("""
                select count(*) from membership m join organization o on o.id = m.org_id
                where o.tenant_id = :t and m.user_id = :u and m.role = 'OWNER'""")
                .param("t", tenant).param("u", user).query(Integer.class).single() > 0;
    }

    // --- plans and subscriptions ---

    /** Row-locks every tenant on the plan until the transaction ends, so limits cannot be raced past. */
    public void lockTenantsOnPlan(String plan) {
        db.sql("select id from customer_tenant where plan_key = :k order by id for update").param("k", plan).query(UUID.class).list();
    }

    public void savePlan(Plan p) {
        db.sql("""
                insert into subscription_plan(key, name, enabled, max_projects, max_channels, monthly_price, currency, features)
                values (:k, :n, :e, :p, :c, :price, :currency, :f)
                on conflict (key) do update set name = excluded.name, enabled = excluded.enabled, max_projects = excluded.max_projects,
                    max_channels = excluded.max_channels, monthly_price = excluded.monthly_price, currency = excluded.currency,
                    features = excluded.features""")
                .param("k", p.key()).param("n", p.name()).param("e", p.enabled()).param("p", p.maxProjects()).param("c", p.maxChannels())
                .param("price", p.monthlyPrice()).param("currency", p.currency()).param("f", String.join(",", p.features())).update();
    }

    /** Optimistic update; false when the tenant changed since expectedRevision. */
    public boolean setSubscription(UUID tenant, String plan, String status, int expectedRevision) {
        return db.sql("update customer_tenant set plan_key = :p, subscription_status = :s, revision = revision + 1 where id = :t and revision = :v")
                .param("p", plan).param("s", status).param("t", tenant).param("v", expectedRevision).update() > 0;
    }

    /** False when the tenant does not exist. */
    public boolean setStatus(UUID tenant, String status) {
        return db.sql("update customer_tenant set status = :s, revision = revision + 1 where id = :t")
                .param("s", status).param("t", tenant).update() > 0;
    }

    // --- projects ---

    public boolean slugTaken(String slug) {
        return db.sql("select count(*) from organization where slug = :s").param("s", slug).query(Integer.class).single() > 0;
    }

    /**
     * New project with its owner and the owned web player as first destination.
     * max_channels on organization is legacy; the tenant plan is the enforced limit.
     */
    public UUID insertProject(UUID tenant, CreateProject r, int maxChannels) {
        UUID id = db.sql("""
                insert into organization(tenant_id, slug, name, country, timezone, kind, max_channels)
                values (:t, :s, :n, :c, :tz, 'CUSTOMER', :max) returning id""")
                .param("t", tenant).param("s", r.slug()).param("n", r.name()).param("c", r.country())
                .param("tz", r.timezone()).param("max", maxChannels).query(UUID.class).single();
        db.sql("insert into membership(org_id, user_id, role) values (:p, :u, 'OWNER')").param("p", id).param("u", r.ownerUserId()).update();
        db.sql("""
                insert into destination(org_id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour)
                values (:p, 'Web player', 'OWNED_WEB', 'SSAI', 'DATERANGE', 720)""").param("p", id).update();
        return id;
    }

    // --- global connector and property definitions ---

    /** Creates or updates a definition; the driver of an existing key is never changed here. */
    public void saveConnector(Connector c, String description) {
        db.sql("""
                insert into connector_definition(key, name, category, driver, enabled, description)
                values (:k, :n, :c, :d, :e, :description)
                on conflict (key) do update set name = excluded.name, category = excluded.category,
                    enabled = excluded.enabled, description = excluded.description""")
                .param("k", c.key()).param("n", c.name()).param("c", c.category()).param("d", c.driver()).param("e", c.enabled())
                .param("description", description).update();
    }

    /** d.options() is the JSON array text; defaultValue is already encoded (sealed when secret). */
    public void insertDefinition(Definition d, String defaultValue) {
        db.sql("""
                insert into property_definition(key, label, value_type, connector_key, secret, project_override, enabled, options, default_value)
                values (:k, :l, :t, :c, :s, :o, :e, :options, :v)""")
                .param("k", d.key()).param("l", d.label()).param("t", d.valueType()).param("c", d.connectorKey())
                .param("s", d.secret()).param("o", d.projectOverride()).param("e", d.enabled())
                .param("options", d.options()).param("v", defaultValue).update();
    }

    /** Optimistic update of the editable fields; false when the definition changed since expectedRevision. */
    public boolean updateDefinition(Definition d, String defaultValue, int expectedRevision) {
        return db.sql("""
                update property_definition set label = :l, project_override = :o, enabled = :e, options = :options,
                    default_value = :v, revision = revision + 1 where key = :k and revision = :rev""")
                .param("l", d.label()).param("o", d.projectOverride()).param("e", d.enabled()).param("options", d.options())
                .param("v", defaultValue).param("k", d.key()).param("rev", expectedRevision).update() > 0;
    }

    public void clearDefault(String key) {
        db.sql("update property_definition set default_value = null, revision = revision + 1 where key = :k").param("k", key).update();
    }
}
