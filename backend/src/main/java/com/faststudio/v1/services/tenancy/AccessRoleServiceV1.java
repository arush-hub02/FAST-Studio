package com.faststudio.v1.services.tenancy;

import com.faststudio.v1.repositories.tenancy.CapabilityRepository;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.repositories.auth.UserAccountRepository;
import com.faststudio.v1.repositories.tenancy.AccessRoleRepository;
import com.faststudio.v1.repositories.tenancy.AccessRoleRepository.Row;

import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Configurable organization roles.
 *
 * <p>Both organization types (CUSTOMER projects and PARTNER organizations) have built-in roles from
 * {@link Tenant.Role}. The platform can define extra named roles per type. A custom role always has a
 * built-in "ceiling" role and may only remove permissions from it — it can never invent new authorization.
 * A member stores the ceiling in {@code membership.role} and the custom role in {@code membership.custom_role_key}.
 */
@Service
public class AccessRoleServiceV1 {

    /** Platform request to create/update a custom role. */
    public record Role(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,49}") String key,
                       @NotBlank @Size(max = 100) String name,
                       @NotNull @Pattern(regexp = "CUSTOMER|PARTNER") String kind,
                       @NotNull String baseRole,
                       @NotNull List<String> permissions,
                       boolean enabled,
                       Integer expectedRevision) {}

    /** A role a member can be given in one organization (built-in or custom), as shown on the Team screen. */
    public record Assignable(String key, String name, String description, String kind, String baseRole, String permissions, boolean builtIn) {}

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

    public AccessRoleServiceV1(AccessRoleRepository repository, UserAccountRepository users, Audit audit, Tokens tokens, CapabilityRepository capabilities) {
        this.capabilities = capabilities;
        this.repository = repository;
        this.users = users;
        this.audit = audit;
        this.tokens = tokens;
    }

    public List<Row> roles() { return repository.roles(); }

    /** Built-in permissions plus enabled global custom permissions (custom ones gate the frontend; they grant no server access). */
    public List<String> catalog() {
        List<String> keys = new ArrayList<>(Arrays.stream(Tenant.Perm.values()).map(Enum::name).toList());
        keys.addAll(TenantScope.bypass(() -> capabilities.enabledCustom("PERMISSION", null, true)));
        return keys;
    }

    /** Built-in plus enabled custom roles available in the caller's organization type. */
    public List<Assignable> assignableIn(Tenant tenant) {
        List<Assignable> result = new ArrayList<>();
        for (Tenant.Role role : Tenant.Role.values()) {
            if (role.partner() != tenant.partnerOrg()) continue;
            String[] text = BUILT_IN.get(role);
            result.add(new Assignable(role.name(), text[0], text[1], tenant.kind(), role.name(),
                    String.join(",", role.perms.stream().map(Enum::name).sorted().toList()), true));
        }
        for (Row r : roles())
            if (r.kind().equals(tenant.kind()) && r.enabled())
                result.add(new Assignable(r.key(), r.name(), "Custom role within " + BUILT_IN.get(Tenant.Role.valueOf(r.baseRole()))[0]
                        + ": " + r.permissions().replace(",", ", "), r.kind(), r.baseRole(), r.permissions(), false));
        return result;
    }

    /** Platform admins create or update a custom role. Kind and ceiling are fixed once created. */
    @Transactional
    public void save(Role role) {
        if (!users.user(Auth.userId()).platformAdmin())
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
        Tenant.Role base;
        try { base = Tenant.Role.valueOf(role.baseRole()); } catch (Exception e) { throw ApiException.bad("Unknown role ceiling."); }
        // OWNER stays a built-in recovery role; partner ceilings only for partner roles and vice versa.
        if (base == Tenant.Role.OWNER || base.partner() != role.kind().equals("PARTNER"))
            throw ApiException.bad("Choose a compatible role ceiling; owner remains a built-in recovery role.");
        Set<String> allowed = new HashSet<>(base.perms.stream().map(Enum::name).toList());
        allowed.addAll(TenantScope.bypass(() -> capabilities.enabledCustom("PERMISSION", null, true)));
        if (!allowed.containsAll(role.permissions()) || !role.permissions().contains("VIEW"))
            throw ApiException.bad("Permissions must include VIEW and stay within the role ceiling.");

        Row old = roles().stream().filter(x -> x.key().equals(role.key())).findFirst().orElse(null);
        if (old != null && (!old.kind().equals(role.kind()) || !old.baseRole().equals(role.baseRole())))
            throw ApiException.bad("Organization type and role ceiling cannot change.");

        String permissions = String.join(",", role.permissions());
        if (old == null) {
            repository.insert(role.key(), role.name(), role.kind(), role.baseRole(), permissions, role.enabled());
        } else {
            if (!repository.update(role.key(), role.name(), permissions, role.enabled(), role.expectedRevision() == null ? -1 : role.expectedRevision())) throw ApiException.conflict("STALE_CONFIGURATION", "Reload the role before saving.");
            // Members holding this role get fresh tokens so changed permissions apply at once.
            tokens.bump(repository.holders(role.key()));
        }
        audit.platform(Auth.userId(), "role.definition.save", "role", null, role.key());
    }

    /** Validates that the caller may hand out a custom role in their organization: same type and no privilege escalation. */
    public Row assignable(Tenant t, String key) {
        Row row = roles().stream().filter(x -> x.key().equals(key) && x.enabled() && x.kind().equals(t.kind())).findFirst()
                .orElseThrow(() -> ApiException.bad("Role is not available for this organization type."));
        Tenant.Role base = Tenant.Role.valueOf(row.baseRole());
        boolean escalates = base.rank > t.role().rank
                || !t.permissions().containsAll(Times.codes(row.permissions()).stream().map(Tenant.Perm::valueOf).toList());
        if (escalates) throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CEILING", "You cannot grant permissions you do not hold.");
        return row;
    }
}
