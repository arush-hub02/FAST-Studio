package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.tenancy.CapabilityDto.*;
import com.faststudio.v1.repositories.tenancy.AccessRoleRepository;
import com.faststudio.v1.repositories.tenancy.CapabilityRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The features and permissions catalogue: platform administrators keep the global entries, a project's administrators
 * (TEAM_MANAGE) its own. Built-in entries mirror what the server enforces and can't be removed or switched off; the
 * others are identifiers for the frontend (and for any endpoint that checks them by key). {@link #permissions} and
 * {@link TenantConfigurationServiceV1#effectiveFeatures} give the keys that apply to a person in a project.
 */
@Service
public class CapabilityServiceV1 {
    private final CapabilityRepository repository;
    private final AccessRoleRepository roles;
    private final TenantConfigurationServiceV1 configuration;
    private final Audit audit;

    public CapabilityServiceV1(CapabilityRepository repository, AccessRoleRepository roles, TenantConfigurationServiceV1 configuration, Audit audit) {
        this.repository = repository;
        this.roles = roles;
        this.configuration = configuration;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ platform: the global catalogue

    public List<Capability> global() { return TenantScope.bypass(repository::global); }

    public Capability createGlobal(CreateRequest r) {
        Capability c = create(r, null);
        audit.platform(Auth.userId(), "capability.create", "capability", c.key(), c.kind() + " " + c.name());
        return c;
    }

    public Capability updateGlobal(String key, UpdateRequest r) {
        Capability c = update(key, r, null);
        audit.platform(Auth.userId(), "capability.update", "capability", key, c.name() + (c.enabled() ? "" : " (off)"));
        return c;
    }

    public void deleteGlobal(String key) {
        delete(key, null);
        audit.platform(Auth.userId(), "capability.delete", "capability", key, key);
    }

    // ------------------------------------------------------------------ a project: its view and its own entries

    /** The catalogue the project can use and the keys effective for this person now. */
    public ProjectView project(Tenant t) {
        t.require(Tenant.Perm.VIEW);
        return new ProjectView(repository.visibleTo(t.orgId()), configuration.effectiveFeatures(t.orgId(), configuration.projectFeatures(t.orgId())),
                permissions(t));
    }

    public Capability createProject(Tenant t, CreateRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Capability c = create(r, t.orgId());
        audit.log(t, "capability.create", "capability", c.key(), c.kind() + " " + c.name());
        return c;
    }

    public Capability updateProject(Tenant t, String key, UpdateRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Capability c = update(key, r, t.orgId());
        audit.log(t, "capability.update", "capability", key, c.name() + (c.enabled() ? "" : " (off)"));
        return c;
    }

    public void deleteProject(Tenant t, String key) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        delete(key, t.orgId());
        audit.log(t, "capability.delete", "capability", key, key);
    }

    /**
     * Permission keys of a person in a project: the built-in ones of their role, plus custom permissions. Owners and
     * administrators get every enabled custom permission; a custom role gets the custom keys it lists; other built-in
     * roles get none until a custom role grants them.
     */
    public List<String> permissions(Tenant t) {
        TreeSet<String> keys = new TreeSet<>(t.permissions().stream().map(Enum::name).toList());
        Optional<String> custom = roles.customPermissions(t.orgId(), t.userId());
        Set<String> available = new HashSet<>(repository.enabledCustom("PERMISSION", t.orgId(), true));
        available.addAll(repository.enabledCustom("PERMISSION", t.orgId(), false));
        if (custom.isPresent()) Times.codes(custom.get()).stream().filter(available::contains).forEach(keys::add);
        else if (!roles.hasCustomRole(t.orgId(), t.userId()) && (t.role() == Tenant.Role.OWNER || t.role() == Tenant.Role.ADMIN)) keys.addAll(available);
        return List.copyOf(keys);
    }

    // ------------------------------------------------------------------ shared rules

    private Capability create(CreateRequest r, UUID org) {
        Capability c = new Capability(r.key(), r.kind(), r.name().trim(), Objects.toString(r.description(), "").trim(),
                category(r.category()), org, false, r.enabled() == null || r.enabled());
        boolean added = org == null ? TenantScope.bypass(() -> repository.insert(c)) : TenantScope.as(org, () -> repository.insert(c));
        if (!added) throw ApiException.conflict("KEY_TAKEN", "The identifier " + r.key() + " is already used. Choose another one.");
        return c;
    }

    private Capability update(String key, UpdateRequest r, UUID org) {
        Capability old = owned(key, org);
        boolean enabled = r.enabled() == null ? old.enabled() : r.enabled();
        if (old.builtIn() && !enabled) throw ApiException.conflict("BUILT_IN", "Built-in entries are enforced by the server and can't be switched off.");
        Capability c = new Capability(key, old.kind(), r.name().trim(), Objects.toString(r.description(), "").trim(), category(r.category()),
                org, old.builtIn(), enabled);
        if (org == null) TenantScope.bypass(() -> repository.update(c)); else TenantScope.as(org, () -> repository.update(c));
        return c;
    }

    private void delete(String key, UUID org) {
        if (owned(key, org).builtIn()) throw ApiException.conflict("BUILT_IN", "Built-in entries are enforced by the server and can't be removed.");
        if (org == null) TenantScope.bypass(() -> repository.delete(key, null)); else TenantScope.as(org, () -> repository.delete(key, org));
    }

    /** The entry, if it belongs to this owner (the global catalogue or this project); 404 otherwise. */
    private Capability owned(String key, UUID org) {
        Optional<Capability> c = org == null ? TenantScope.bypass(() -> repository.find(key)) : TenantScope.as(org, () -> repository.find(key));
        return c.filter(x -> Objects.equals(x.orgId(), org)).orElseThrow(() -> ApiException.notFound("Feature or permission"));
    }

    private static String category(String c) { return c == null || c.isBlank() ? "General" : c.trim(); }
}
