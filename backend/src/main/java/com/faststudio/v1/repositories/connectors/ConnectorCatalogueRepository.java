package com.faststudio.v1.repositories.connectors;

import com.faststudio.v1.dto.connectors.ConnectorCatalogueDto.Category;
import com.faststudio.v1.dto.connectors.ConnectorCatalogueDto.CategoryRequest;
import com.faststudio.v1.dto.connectors.ConnectorCatalogueDto.PropertyRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * SQL for the connector marketplace (V26): categories, connector metadata, property definitions, and the instances and
 * values of projects (org_id) or of the platform (org_id null). Values of encrypted properties arrive and leave sealed;
 * this class never decrypts. Instances and values follow row-level security; platform-level ones need the system login.
 */
@Repository
public class ConnectorCatalogueRepository {
    private final JdbcClient db;

    public ConnectorCatalogueRepository(JdbcClient db) { this.db = db; }

    public record ConnectorRow(String key, String name, String category, String driver, boolean enabled, String description, String longDescription,
                               String logoUrl, String darkLogoUrl, String referenceLink, String tier, String status, String tenantUpdate,
                               String requiredPermission, String level, boolean multiInstance) {}

    public record PropertyRow(String connectorKey, String key, String label, String description, String valueType, String options,
                              String defaultValue, int position, boolean required, boolean encrypted, String scope) {}

    public record InstanceRow(UUID id, String connectorKey, UUID orgId, String name, int position, boolean enabled,
                              OffsetDateTime checkedAt, String checkError) {}

    public record ValueRow(UUID instanceId, String propertyKey, String value) {}

    // ------------------------------------------------------------------ categories

    public List<Category> categories() {
        return db.sql("select key, name, description, position from connector_category order by position, name").query(Category.class).list();
    }

    public void saveCategory(CategoryRequest r, boolean create) {
        db.sql(create ? "insert into connector_category(key, name, description, position) values (:k, :n, :d, :p)"
                        : "update connector_category set name = :n, description = :d, position = :p where key = :k")
                .param("k", r.key()).param("n", r.name().trim()).param("d", Objects.toString(r.description(), "").trim())
                .param("p", r.position() == null ? 0 : r.position()).update();
    }

    public boolean categoryExists(String key) {
        return db.sql("select count(*) from connector_category where key = :k").param("k", key).query(Integer.class).single() > 0;
    }

    public boolean categoryInUse(String key) {
        return db.sql("select count(*) from connector_definition where category = :k").param("k", key).query(Integer.class).single() > 0;
    }

    public int deleteCategory(String key) { return db.sql("delete from connector_category where key = :k").param("k", key).update(); }

    // ------------------------------------------------------------------ connectors and properties

    public List<ConnectorRow> connectors() {
        return db.sql("""
                select key, name, category, driver, enabled, description, long_description, logo_url, dark_logo_url, reference_link,
                       tier, status, tenant_update, required_permission, level, multi_instance
                from connector_definition order by category, name""").query(ConnectorRow.class).list();
    }

    /** The marketplace details of an existing connector (its key, driver and basic fields go through the definition save). */
    public void saveDetails(String key, String longDescription, String logoUrl, String darkLogoUrl, String referenceLink, String tier, String status,
                            String tenantUpdate, String requiredPermission, String level, boolean multiInstance) {
        Map<String, Object> p = new HashMap<>(Map.of("k", key, "ld", longDescription, "t", tier, "s", status, "u", tenantUpdate, "lv", level, "m", multiInstance));
        p.put("logo", logoUrl);
        p.put("dark", darkLogoUrl);
        p.put("ref", referenceLink);
        p.put("perm", requiredPermission);
        db.sql("""
                update connector_definition set long_description = :ld, logo_url = :logo, dark_logo_url = :dark, reference_link = :ref, tier = :t,
                  status = :s, tenant_update = :u, required_permission = :perm, level = :lv, multi_instance = :m where key = :k""").params(p).update();
    }

    public List<PropertyRow> properties() {
        return db.sql("""
                select connector_key, key, label, description, value_type, options, default_value, position, required, encrypted, scope
                from connector_property order by connector_key, position, key""").query(PropertyRow.class).list();
    }

    public void saveProperty(String connector, PropertyRequest r, String optionsJson) {
        Map<String, Object> p = new HashMap<>(Map.of("c", connector, "k", r.key(), "l", r.label().trim(), "d", Objects.toString(r.description(), "").trim(),
                "t", r.valueType(), "o", optionsJson, "pos", r.position() == null ? 0 : r.position(), "req", r.required(), "enc", r.encrypted(), "s", r.scope()));
        p.put("def", r.encrypted() || r.defaultValue() == null || r.defaultValue().isBlank() ? null : r.defaultValue().trim());
        db.sql("""
                insert into connector_property(connector_key, key, label, description, value_type, options, default_value, position, required, encrypted, scope)
                values (:c, :k, :l, :d, :t, :o, :def, :pos, :req, :enc, :s)
                on conflict (connector_key, key) do update set label = excluded.label, description = excluded.description, value_type = excluded.value_type,
                  options = excluded.options, default_value = excluded.default_value, position = excluded.position, required = excluded.required,
                  encrypted = excluded.encrypted, scope = excluded.scope""").params(p).update();
    }

    public int deleteProperty(String connector, String key) {
        return db.sql("delete from connector_property where connector_key = :c and key = :k").param("c", connector).param("k", key).update();
    }

    /** Values stored for a property that was removed or whose encryption changed, across every project and the platform. */
    public void deleteValuesOf(String connector, String key) {
        db.sql("""
                delete from connector_value v using connector_instance i
                where v.instance_id = i.id and i.connector_key = :c and v.property_key = :k /* all tenants: a property definition changed */""")
                .param("c", connector).param("k", key).update();
    }

    // ------------------------------------------------------------------ instances and values

    /** Instances of one project (org) or of the platform (org null). */
    public List<InstanceRow> instances(UUID org) {
        return db.sql("""
                select id, connector_key, org_id, name, position, enabled, checked_at, check_error from connector_instance
                where org_id is not distinct from cast(:o as uuid) order by connector_key, position""")
                .param("o", org).query(InstanceRow.class).list();
    }

    public List<ValueRow> values(UUID org) {
        return db.sql("select instance_id, property_key, value from connector_value where org_id is not distinct from cast(:o as uuid)")
                .param("o", org).query(ValueRow.class).list();
    }

    public int nextPosition(String connector, UUID org) {
        return db.sql("select coalesce(max(position), 0) + 1 from connector_instance where connector_key = :c and org_id is not distinct from cast(:o as uuid)")
                .param("c", connector).param("o", org).query(Integer.class).single();
    }

    public UUID insertInstance(String connector, UUID org, String name, int position, boolean enabled) {
        Map<String, Object> p = new HashMap<>(Map.of("c", connector, "n", name, "p", position, "e", enabled));
        p.put("o", org);
        return db.sql("insert into connector_instance(connector_key, org_id, name, position, enabled) values (:c, :o, :n, :p, :e) returning id")
                .params(p).query(UUID.class).single();
    }

    public boolean updateInstance(UUID id, UUID org, String name, boolean enabled) {
        Map<String, Object> p = new HashMap<>(Map.of("id", id, "n", name, "e", enabled));
        p.put("o", org);
        return db.sql("update connector_instance set name = :n, enabled = :e, updated_at = now() where id = :id and org_id is not distinct from cast(:o as uuid)")
                .params(p).update() > 0;
    }

    public int deleteInstance(UUID id, UUID org) {
        Map<String, Object> p = new HashMap<>(Map.of("id", id));
        p.put("o", org);
        return db.sql("delete from connector_instance where id = :id and org_id is not distinct from cast(:o as uuid)").params(p).update();
    }

    /** Records a health check of a connection (error null = it worked); clear = forget it (new values need a new check). */
    public void checked(UUID instance, UUID org, String error, boolean clear) {
        Map<String, Object> p = new HashMap<>(Map.of("id", instance, "c", clear));
        p.put("o", org);
        p.put("e", error);
        db.sql("""
                update connector_instance set checked_at = case when :c then null else now() end, check_error = case when :c then null else :e end
                where id = :id and org_id is not distinct from cast(:o as uuid)""").params(p).update();
    }

    public void putValue(UUID instance, UUID org, String key, String value) {
        Map<String, Object> p = new HashMap<>(Map.of("i", instance, "k", key, "v", value));
        p.put("o", org);
        db.sql("""
                insert into connector_value(instance_id, org_id, property_key, value) values (:i, :o, :k, :v)
                on conflict (instance_id, property_key) do update set value = excluded.value, updated_at = now()""").params(p).update();
    }

    public void removeValue(UUID instance, UUID org, String key) {
        Map<String, Object> p = new HashMap<>(Map.of("i", instance, "k", key));
        p.put("o", org);
        db.sql("delete from connector_value where instance_id = :i and property_key = :k and org_id is not distinct from cast(:o as uuid)").params(p).update();
    }
}
