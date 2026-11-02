package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.tenancy.CatalogueDto.*;
import com.faststudio.v1.repositories.tenancy.AccessRoleRepository;
import com.faststudio.v1.repositories.tenancy.CapabilityRepository;
import com.faststudio.v1.repositories.tenancy.CatalogueRepository;
import com.faststudio.v1.repositories.tenancy.TenantConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The global catalogue (docs/PLATFORM_ADMIN_PLAN.md): platform administrators keep modules, feature groups, features and
 * permissions; a project links global features (TEAM_MANAGE). Built-in features and permissions mirror what the server
 * enforces: they can be renamed and regrouped, never switched off or removed, and built-in features stay switched on per
 * project by the platform only (they follow the tenant's plan). {@link #permissions} gives a person's permission keys.
 */
@Service
public class CatalogueServiceV1 {
    private final CatalogueRepository catalogue;
    private final CapabilityRepository capabilities;
    private final AccessRoleRepository roles;
    private final TenantConfigurationRepository projects;
    private final TenantConfigurationServiceV1 configuration;
    private final TransactionTemplate tx;
    private final Audit audit;

    public CatalogueServiceV1(CatalogueRepository catalogue, CapabilityRepository capabilities, AccessRoleRepository roles,
                              TenantConfigurationRepository projects, TenantConfigurationServiceV1 configuration, TransactionTemplate tx, Audit audit) {
        this.catalogue = catalogue;
        this.capabilities = capabilities;
        this.roles = roles;
        this.projects = projects;
        this.configuration = configuration;
        this.tx = tx;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    public Catalogue catalogue() {
        return TenantScope.bypass(() -> new Catalogue(catalogue.modules(), catalogue.groups(), catalogue.features(), catalogue.permissions()));
    }

    // ------------------------------------------------------------------ platform: modules and feature groups

    public ModuleEntry saveModule(ModuleRequest r, boolean create) {
        system(() -> {
            if (create == catalogue.moduleExists(r.key())) throw create ? taken(r.key()) : ApiException.notFound("ModuleEntry");
            catalogue.saveModule(r, create);
        });
        log("module.save", r.key());
        return catalogue().modules().stream().filter(m -> m.key().equals(r.key())).findFirst().orElseThrow();
    }

    public void deleteModule(String key) {
        system(() -> {
            if (catalogue.moduleInUse(key)) throw ApiException.conflict("IN_USE", "Move or remove the module's feature groups first.");
            if (catalogue.deleteModule(key) == 0) throw ApiException.notFound("ModuleEntry");
        });
        log("module.delete", key);
    }

    public Group saveGroup(GroupRequest r, boolean create) {
        system(() -> {
            if (create == catalogue.groupExists(r.key())) throw create ? taken(r.key()) : ApiException.notFound("Feature group");
            if (!catalogue.moduleExists(r.moduleKey())) throw ApiException.bad("Unknown module " + r.moduleKey() + ".");
            catalogue.saveGroup(r, create);
        });
        log("feature_group.save", r.key());
        return catalogue().groups().stream().filter(g -> g.key().equals(r.key())).findFirst().orElseThrow();
    }

    public void deleteGroup(String key) {
        system(() -> {
            if (catalogue.groupInUse(key)) throw ApiException.conflict("IN_USE", "Move or remove the group's features first.");
            if (catalogue.deleteGroup(key) == 0) throw ApiException.notFound("Feature group");
        });
        log("feature_group.delete", key);
    }

    // ------------------------------------------------------------------ platform: features and permissions

    public Feature saveFeature(FeatureRequest r, boolean create) {
        system(() -> {
            Optional<Map<String, Object>> existing = catalogue.entry(r.key());
            if (create && existing.isPresent()) throw taken(r.key());
            if (!create && existing.filter(e -> "FEATURE".equals(e.get("kind"))).isEmpty()) throw ApiException.notFound("Feature");
            if (!create && Boolean.TRUE.equals(existing.get().get("built_in")) && Boolean.FALSE.equals(r.enabled())) throw builtIn();
            if (!catalogue.groupExists(r.groupKey())) throw ApiException.bad("Unknown feature group " + r.groupKey() + ".");
            Set<String> unknown = new TreeSet<>(r.permissions());
            unknown.removeAll(catalogue.permissionKeys());
            if (!unknown.isEmpty()) throw ApiException.bad("Unknown permissions: " + String.join(", ", unknown) + ".");
            if (create) catalogue.insertFeature(r); else catalogue.updateFeature(r);
            catalogue.setFeaturePermissions(r.key(), r.permissions());
        });
        log("feature.save", r.key());
        return catalogue().features().stream().filter(f -> f.key().equals(r.key())).findFirst().orElseThrow();
    }

    public Permission savePermission(PermissionRequest r, boolean create) {
        system(() -> {
            Optional<Map<String, Object>> existing = catalogue.entry(r.key());
            if (create && existing.isPresent()) throw taken(r.key());
            if (!create && existing.filter(e -> "PERMISSION".equals(e.get("kind"))).isEmpty()) throw ApiException.notFound("Permission");
            if (!create && Boolean.TRUE.equals(existing.get().get("built_in")) && Boolean.FALSE.equals(r.enabled())) throw builtIn();
            if (create) catalogue.insertPermission(r); else catalogue.updatePermission(r);
        });
        log("permission.save", r.key());
        return catalogue().permissions().stream().filter(p -> p.key().equals(r.key())).findFirst().orElseThrow();
    }

    /** Removes an added feature or permission; built-in ones can't be removed. */
    public void deleteEntry(String key, String kind) {
        system(() -> {
            Map<String, Object> e = catalogue.entry(key).filter(x -> kind.equals(x.get("kind")))
                    .orElseThrow(() -> ApiException.notFound(kind.equals("FEATURE") ? "Feature" : "Permission"));
            if (Boolean.TRUE.equals(e.get("built_in"))) throw builtIn();
            catalogue.deleteEntry(key, kind);
        });
        log(kind.toLowerCase(Locale.ROOT) + ".delete", key);
    }

    // ------------------------------------------------------------------ a project: linking global features

    /** What the project can link (published groups, enabled features that aren't hidden), what it has, and the caller's keys. */
    public ProjectFeatures project(Tenant t) {
        t.require(Tenant.Perm.VIEW);
        Catalogue c = catalogue();
        Set<String> published = c.groups().stream().filter(Group::published).map(Group::key).collect(Collectors.toSet());
        List<Feature> available = c.features().stream().filter(f -> f.enabled() && !f.hidden() && published.contains(f.groupKey())).toList();
        String linked = configuration.projectFeatures(t.orgId());
        return new ProjectFeatures(c.modules().stream().filter(ModuleEntry::enabled).toList(), c.groups().stream().filter(Group::published).toList(),
                available, Times.codes(linked), configuration.effectiveFeatures(t.orgId(), linked), permissions(t));
    }

    /** Switches a global feature on (link) or off (unlink) for the project. Built-in features are the platform's to switch. */
    public ProjectFeatures link(Tenant t, String key, boolean on) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Feature f = project(t).available().stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Feature"));
        if (f.builtIn()) throw ApiException.conflict("PLATFORM_ONLY", "This feature is part of the subscription; the platform administrator switches it on.");
        update(t, keys -> { if (on) keys.add(key); else keys.remove(key); });
        audit.log(t, on ? "feature.link" : "feature.unlink", "feature", key, f.name());
        return project(t);
    }

    /** Links every available default feature that isn't built in. */
    public ProjectFeatures linkDefaults(Tenant t) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        List<String> defaults = project(t).available().stream().filter(f -> f.isDefault() && !f.builtIn()).map(Feature::key).toList();
        update(t, keys -> keys.addAll(defaults));
        audit.log(t, "feature.link_defaults", "feature", null, defaults.isEmpty() ? "none" : String.join(", ", defaults));
        return project(t);
    }

    private void update(Tenant t, java.util.function.Consumer<Set<String>> change) {
        TenantScope.bypass(() -> tx.execute(s -> {
            Set<String> keys = new LinkedHashSet<>(Times.codes(projects.projectFeatures(t.orgId())));
            change.accept(keys);
            projects.setProjectFeatures(t.orgId(), String.join(",", keys));
            return null;
        }));
    }

    // ------------------------------------------------------------------ a person's permissions

    /**
     * Permission keys of a person in a project: the built-in ones of their role, plus custom permissions. Owners and
     * administrators get every enabled custom permission; a custom role (global or the project's own) gets the custom
     * keys it lists; other built-in roles get none until a custom role grants them.
     */
    public List<String> permissions(Tenant t) {
        TreeSet<String> keys = new TreeSet<>(t.permissions().stream().map(Enum::name).toList());
        Set<String> custom = new HashSet<>(TenantScope.bypass(() -> capabilities.enabledCustom("PERMISSION", null, true)));
        Optional<String> role = roles.customPermissions(t.orgId(), t.userId());
        if (role.isPresent()) Times.codes(role.get()).stream().filter(custom::contains).forEach(keys::add);
        else if (!roles.hasCustomRole(t.orgId(), t.userId()) && (t.role() == Tenant.Role.OWNER || t.role() == Tenant.Role.ADMIN)) keys.addAll(custom);
        return List.copyOf(keys);
    }

    // ------------------------------------------------------------------ helpers

    private void system(Runnable work) { TenantScope.bypass(() -> tx.execute(s -> { work.run(); return null; })); }

    private void log(String action, String key) { audit.platform(Auth.userId(), "catalogue." + action, "catalogue", key, key); }

    private static ApiException taken(String key) { return ApiException.conflict("KEY_TAKEN", "The identifier " + key + " is already used. Choose another one."); }

    private static ApiException builtIn() { return ApiException.conflict("BUILT_IN", "Built-in entries are enforced by the server and can't be switched off or removed."); }
}
