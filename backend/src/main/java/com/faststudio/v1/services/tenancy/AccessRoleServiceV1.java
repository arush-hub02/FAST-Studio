package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.auth.UserAccountRepository;
import com.faststudio.v1.repositories.tenancy.AccessRoleRepository;
import com.faststudio.v1.repositories.tenancy.AccessRoleRepository.Row;
import com.faststudio.v1.repositories.tenancy.CapabilityRepository;
import com.faststudio.v1.repositories.tenancy.CatalogueRepository;
import com.faststudio.v1.dto.tenancy.CatalogueDto;

import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Configurable organization roles.
 *
 * <p>Both organization types (CUSTOMER projects and PARTNER organizations) have built-in roles from
 * {@link Tenant.Role}. The platform defines global named roles per type; a project's administrators (TEAM_MANAGE) can
 * define roles for that project only. A custom role always has a built-in "ceiling" role and may only remove built-in
 * permissions from it — it can never invent new server authorization — plus any custom permissions from the catalogue
 * (those only show or hide things in screens). A member stores the ceiling in {@code membership.role} and the custom
 * role in {@code membership.custom_role_key}. The role editor groups permissions by module, feature group and feature
 * (GET /api/platform/catalogue).
 */
@Service
public class AccessRoleServiceV1 {

    /** Request to create/update a custom role. */
    public record Role(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,49}") String key,
                       @NotBlank @Size(max = 100) String name,
                       @NotNull @Pattern(regexp = "CUSTOMER|PARTNER") String kind,
                       @NotNull String baseRole,
                       @NotNull List<String> permissions,
                       boolean enabled,
                       Integer expectedRevision) {}

    /** A role a member can be given in one organization (built-in or custom), as shown on the Team screen. */
    public record Assignable(String key, String name, String description, String kind, String baseRole, String permissions, boolean builtIn) {}

    /** A project's roles: the global ones (read-only here) and its own; permissions = every key a role may list. */
    public record ProjectRoles(List<Row> roles, List<String> permissions, Map<String, List<String>> ceilings, CatalogueDto.Catalogue catalogue) {}

    /** Friendly names and descriptions for built-in roles. */
    private static final Map<Tenant.Role, String[]> BUILT_IN = Map.of(
            Tenant.Role.OWNER, new String[]{"Owner", "Everything, including managing other owners"},
            Tenant.Role.ADMIN, new String[]{"Admin", "Everything except managing owners"},
            Tenant.Role.CONTENT_MANAGER, new String[]{"Content manager", "Videos, break points and licences"},
            Tenant.Role.CHANNEL_OPERATOR, new String[]{"Channel operator", "Channels, schedules, publishing, going live, distribution"},
            Tenant.Role.AD_OPS, new String[]{"Ad operations", "Campaigns and ad decisions"},
            Tenant.Role.ANALYST, new String[]{"Analyst", "Can see everything, change nothing"},
            Tenant.Role.PARTNER_ADMIN, new String[]{"Partner admin", "Accept sharings, manage API clients and people"},
            Tenant.Role.PARTNER_VIEWER, new String[]{"Partner viewer", "See what customers share, change nothing"});

    private final AccessRoleRepository repository;
    private final UserAccountRepository users;
    private final Audit audit;
    private final Tokens tokens;
    private final CapabilityRepository capabilities;
    private final CatalogueRepository catalogue;

    public AccessRoleServiceV1(AccessRoleRepository repository, UserAccountRepository users, Audit audit, Tokens tokens, CapabilityRepository capabilities,
                               CatalogueRepository catalogue) {
        this.catalogue = catalogue;
        this.repository = repository;
        this.users = users;
        this.audit = audit;
        this.tokens = tokens;
        this.capabilities = capabilities;
    }

    /** Per role ceiling (not OWNER), the permission keys a custom role under it may list: its built-ins plus custom ones. */
    public Map<String, List<String>> ceilings() {
        List<String> custom = customPermissions();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Tenant.Role r : Tenant.Role.values()) {
            if (r == Tenant.Role.OWNER) continue;
            List<String> keys = new ArrayList<>(r.perms.stream().map(Enum::name).sorted().toList());
            keys.addAll(custom);
            out.put(r.name(), keys);
        }
        return out;
    }

    /** Global roles (platform administration). */
    public List<Row> roles() { return TenantScope.bypass(repository::globalRoles); }

    /** Built-in permissions plus enabled custom permissions (custom ones gate the frontend; they grant no server access). */
    public List<String> catalog() {
        List<String> keys = new ArrayList<>(Arrays.stream(Tenant.Perm.values()).map(Enum::name).toList());
        keys.addAll(customPermissions());
        return keys;
    }

    private List<String> customPermissions() { return TenantScope.bypass(() -> capabilities.enabledCustom("PERMISSION", null, true)); }

    /** Built-in plus enabled custom roles (global and the project's own) available in the caller's organization. */
    public List<Assignable> assignableIn(Tenant tenant) {
        List<Assignable> result = new ArrayList<>();
        for (Tenant.Role role : Tenant.Role.values()) {
            if (role.partner() != tenant.partnerOrg()) continue;
            String[] text = BUILT_IN.get(role);
            result.add(new Assignable(role.name(), text[0], text[1], tenant.kind(), role.name(),
                    String.join(",", role.perms.stream().map(Enum::name).sorted().toList()), true));
        }
        for (Row r : repository.rolesFor(tenant.orgId()))
            if (r.kind().equals(tenant.kind()) && r.enabled())
                result.add(new Assignable(r.key(), r.name(), (r.orgId() == null ? "Custom role within " : "Project role within ")
                        + BUILT_IN.get(Tenant.Role.valueOf(r.baseRole()))[0] + ": " + r.permissions().replace(",", ", "),
                        r.kind(), r.baseRole(), r.permissions(), false));
        return result;
    }

    /** Platform admins create or update a global custom role. Kind and ceiling are fixed once created. */
    public void save(Role role) {
        if (!users.user(Auth.userId()).platformAdmin())
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
        checkDefinition(role, null);
        TenantScope.bypass(() -> { store(role, null); return null; });
        audit.platform(Auth.userId(), "role.definition.save", "role", null, role.key());
    }

    // ------------------------------------------------------------------ a project's own roles

    public ProjectRoles projectRoles(Tenant t) {
        t.require(Tenant.Perm.VIEW);
        return new ProjectRoles(repository.rolesFor(t.orgId()).stream().filter(r -> r.kind().equals(t.kind())).toList(), catalog(), ceilings(),
                TenantScope.bypass(() -> new CatalogueDto.Catalogue(catalogue.modules(), catalogue.groups(), catalogue.features(), catalogue.permissions())));
    }

    /** A project's administrators create or update a role for this project; its ceiling can't be above their own role. */
    public ProjectRoles saveProjectRole(Tenant t, Role role) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        if (!role.kind().equals(t.kind())) throw ApiException.bad("Roles of a project are for its own organization type.");
        checkDefinition(role, t);
        TenantScope.as(t.orgId(), () -> { store(role, t.orgId()); return null; });
        audit.log(t, "role.definition.save", "role", null, role.key());
        return projectRoles(t);
    }

    /** Deletes one of the project's own roles; refused while anyone holds it. */
    public ProjectRoles deleteProjectRole(Tenant t, String key) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Row row = repository.rolesFor(t.orgId()).stream().filter(r -> r.key().equals(key) && t.orgId().equals(r.orgId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Role"));
        if (!repository.holders(row.key()).isEmpty())
            throw ApiException.conflict("IN_USE", "Give the people with this role another role first.");
        repository.delete(key, t.orgId());
        audit.log(t, "role.definition.delete", "role", null, key);
        return projectRoles(t);
    }

    // ------------------------------------------------------------------ shared rules

    /** Ceiling compatible with the type, never OWNER, not above the caller's own role; permissions within the ceiling plus custom ones. */
    private void checkDefinition(Role role, Tenant caller) {
        Tenant.Role base;
        try { base = Tenant.Role.valueOf(role.baseRole()); } catch (Exception e) { throw ApiException.bad("Unknown role ceiling."); }
        // OWNER stays a built-in recovery role; partner ceilings only for partner roles and vice versa.
        if (base == Tenant.Role.OWNER || base.partner() != role.kind().equals("PARTNER"))
            throw ApiException.bad("Choose a compatible role ceiling; owner remains a built-in recovery role.");
        if (caller != null && base.rank > caller.role().rank)
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CEILING", "A role can't reach above your own role.");
        Set<String> allowed = new HashSet<>(base.perms.stream().map(Enum::name).toList());
        allowed.addAll(customPermissions());
        if (!allowed.containsAll(role.permissions()) || !role.permissions().contains("VIEW"))
            throw ApiException.bad("Permissions must include VIEW and stay within the role ceiling.");
    }

    /** Inserts or updates a role owned by {@code org} (null = global); a key taken by anyone else is refused. */
    private void store(Role role, UUID org) {
        Row old = (org == null ? repository.globalRoles() : repository.rolesFor(org)).stream()
                .filter(x -> x.key().equals(role.key()) && Objects.equals(x.orgId(), org)).findFirst().orElse(null);
        if (old != null && (!old.kind().equals(role.kind()) || !old.baseRole().equals(role.baseRole())))
            throw ApiException.bad("Organization type and role ceiling cannot change.");
        String permissions = String.join(",", new LinkedHashSet<>(role.permissions()));
        if (old == null) {
            if (TenantScope.bypass(() -> repository.exists(role.key())))
                throw ApiException.conflict("KEY_TAKEN", "The role key " + role.key() + " is already used. Choose another one.");
            repository.insert(role.key(), role.name(), role.kind(), role.baseRole(), permissions, role.enabled(), org);
        } else {
            if (!repository.update(role.key(), role.name(), permissions, role.enabled(), role.expectedRevision() == null ? -1 : role.expectedRevision(), org))
                throw ApiException.conflict("STALE_CONFIGURATION", "Reload the role before saving.");
            // Members holding this role get fresh tokens so changed permissions apply at once.
            tokens.bump(repository.holders(role.key()));
        }
    }

    /** Validates that the caller may hand out a custom role in their organization: same type and no privilege escalation. */
    public Row assignable(Tenant t, String key) {
        Row row = repository.rolesFor(t.orgId()).stream().filter(x -> x.key().equals(key) && x.enabled() && x.kind().equals(t.kind())).findFirst()
                .orElseThrow(() -> ApiException.bad("Role is not available for this organization type."));
        Tenant.Role base = Tenant.Role.valueOf(row.baseRole());
        Set<String> builtIn = new HashSet<>(Arrays.stream(Tenant.Perm.values()).map(Enum::name).toList());
        // only built-in permissions are server authorization; custom keys are screen identifiers
        boolean escalates = base.rank > t.role().rank || !t.permissions().containsAll(Times.codes(row.permissions()).stream()
                .filter(builtIn::contains).map(Tenant.Perm::valueOf).toList());
        if (escalates) throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CEILING", "You cannot grant permissions you do not hold.");
        return row;
    }
}
