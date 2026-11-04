package com.faststudio.v1.repositories.tenancy;

import com.faststudio.v1.dto.tenancy.CatalogueDto.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * SQL for the global catalogue: modules, feature groups, features and permissions (features and permissions are rows of
 * `capability`, global only since V25) and which permissions each feature uses. Writes run on the system login.
 */
@Repository
public class CatalogueRepository {
    private final JdbcClient db;

    public CatalogueRepository(JdbcClient db) { this.db = db; }

    private record FeatureRow(String key, String name, String description, String groupKey, boolean isDefault, boolean hidden,
                              boolean beta, boolean enabled, boolean builtIn) {}

    private record Link(String featureKey, String permissionKey) {}

    public List<ModuleEntry> modules() {
        return db.sql("select key, name, description, position, enabled from module order by position, name").query(ModuleEntry.class).list();
    }

    public List<Group> groups() {
        return db.sql("select key, module_key, name, description, position, published from feature_group order by position, name").query(Group.class).list();
    }

    public List<Feature> features() {
        Map<String, List<String>> uses = new HashMap<>();
        db.sql("select feature_key, permission_key from feature_permission order by permission_key").query(Link.class).list()
                .forEach(l -> uses.computeIfAbsent(l.featureKey(), k -> new ArrayList<>()).add(l.permissionKey()));
        return db.sql("""
                select key, name, description, group_key, is_default, hidden, beta, enabled, built_in from capability
                where kind = 'FEATURE' and org_id is null order by name""").query(FeatureRow.class).list().stream()
                .map(f -> new Feature(f.key(), f.name(), f.description(), f.groupKey(), uses.getOrDefault(f.key(), List.of()),
                        f.isDefault(), f.hidden(), f.beta(), f.enabled(), f.builtIn())).toList();
    }

    public List<Permission> permissions() {
        return db.sql("select key, name, description, category, enabled, built_in from capability where kind = 'PERMISSION' and org_id is null order by category, name")
                .query(Permission.class).list();
    }

    // ------------------------------------------------------------------ modules and groups

    public void saveModule(ModuleRequest r, boolean create) {
        db.sql(create ? "insert into module(key, name, description, position, enabled) values (:k, :n, :d, :p, :e)"
                        : "update module set name = :n, description = :d, position = :p, enabled = :e where key = :k")
                .param("k", r.key()).param("n", r.name()).param("d", Objects.toString(r.description(), ""))
                .param("p", r.position() == null ? 0 : r.position()).param("e", r.enabled() == null || r.enabled()).update();
    }

    public boolean moduleExists(String key) {
        return db.sql("select count(*) from module where key = :k").param("k", key).query(Integer.class).single() > 0;
    }

    public int deleteModule(String key) { return db.sql("delete from module where key = :k").param("k", key).update(); }

    public void saveGroup(GroupRequest r, boolean create) {
        db.sql(create ? "insert into feature_group(key, module_key, name, description, position, published) values (:k, :m, :n, :d, :p, :pub)"
                        : "update feature_group set module_key = :m, name = :n, description = :d, position = :p, published = :pub where key = :k")
                .param("k", r.key()).param("m", r.moduleKey()).param("n", r.name()).param("d", Objects.toString(r.description(), ""))
                .param("p", r.position() == null ? 0 : r.position()).param("pub", r.published() == null || r.published()).update();
    }

    public boolean groupExists(String key) {
        return db.sql("select count(*) from feature_group where key = :k").param("k", key).query(Integer.class).single() > 0;
    }

    public int deleteGroup(String key) { return db.sql("delete from feature_group where key = :k").param("k", key).update(); }

    public boolean moduleInUse(String key) {
        return db.sql("select count(*) from feature_group where module_key = :k").param("k", key).query(Integer.class).single() > 0;
    }

    public boolean groupInUse(String key) {
        return db.sql("select count(*) from capability where group_key = :k and org_id is null").param("k", key).query(Integer.class).single() > 0;
    }

    // ------------------------------------------------------------------ features and permissions (capability rows)

    /** The kind and built-in flag of a key, if any entry uses it. */
    public Optional<Map<String, Object>> entry(String key) {
        return db.sql("select kind, built_in from capability where key = :k and org_id is null").param("k", key).query().listOfRows().stream().findFirst();
    }

    public void insertFeature(FeatureRequest r) {
        db.sql("""
                insert into capability(key, kind, name, description, category, group_key, is_default, hidden, beta, enabled)
                values (:k, 'FEATURE', :n, :d, 'General', :g, :def, :h, :b, :e) /* all tenants: global catalogue row (org_id null) */""").params(feature(r)).update();
    }

    /** Built-in features keep their switch on; everything else is editable. */
    public void updateFeature(FeatureRequest r) {
        db.sql("""
                update capability set name = :n, description = :d, group_key = :g, is_default = :def, hidden = :h, beta = :b,
                  enabled = case when built_in then true else :e end
                where key = :k and kind = 'FEATURE' and org_id is null""").params(feature(r)).update();
    }

    public void setFeaturePermissions(String feature, Collection<String> permissions) {
        db.sql("delete from feature_permission where feature_key = :f").param("f", feature).update();
        for (String p : new LinkedHashSet<>(permissions))
            db.sql("insert into feature_permission(feature_key, permission_key) values (:f, :p)").param("f", feature).param("p", p).update();
    }

    public void insertPermission(PermissionRequest r) {
        db.sql("insert into capability(key, kind, name, description, category, enabled) values (:k, 'PERMISSION', :n, :d, :c, :e) /* all tenants: global catalogue row (org_id null) */")
                .params(permission(r)).update();
    }

    public void updatePermission(PermissionRequest r) {
        db.sql("""
                update capability set name = :n, description = :d, category = :c, enabled = case when built_in then true else :e end
                where key = :k and kind = 'PERMISSION' and org_id is null""").params(permission(r)).update();
    }

    /** Removes an added feature or permission (never a built-in one). */
    public int deleteEntry(String key, String kind) {
        return db.sql("delete from capability where key = :k and kind = :kind and not built_in and org_id is null")
                .param("k", key).param("kind", kind).update();
    }

    /** Permission keys that exist (enabled or not). */
    public Set<String> permissionKeys() {
        return new HashSet<>(db.sql("select key from capability where kind = 'PERMISSION' and org_id is null").query(String.class).list());
    }

    private static Map<String, Object> feature(FeatureRequest r) {
        return Map.of("k", r.key(), "n", r.name().trim(), "d", Objects.toString(r.description(), "").trim(), "g", r.groupKey(),
                "def", Boolean.TRUE.equals(r.isDefault()), "h", Boolean.TRUE.equals(r.hidden()), "b", Boolean.TRUE.equals(r.beta()),
                "e", r.enabled() == null || r.enabled());
    }

    private static Map<String, Object> permission(PermissionRequest r) {
        return Map.of("k", r.key(), "n", r.name().trim(), "d", Objects.toString(r.description(), "").trim(),
                "c", r.category() == null || r.category().isBlank() ? "General" : r.category().trim(), "e", r.enabled() == null || r.enabled());
    }
}
