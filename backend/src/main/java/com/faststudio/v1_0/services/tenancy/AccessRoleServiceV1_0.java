package com.faststudio.v1_0.services.tenancy;

import com.faststudio.v1_0.core.auth.Auth;
import com.faststudio.v1_0.core.auth.Tokens;
import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Times;
import com.faststudio.v1_0.core.tenant.Tenant;

import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
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
public class AccessRoleServiceV1_0 {

    /** Platform request to create/update a custom role. */
    public record Role(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,49}") String key,
                       @NotBlank @Size(max = 100) String name,
                       @NotNull @Pattern(regexp = "CUSTOMER|PARTNER") String kind,
                       @NotNull String baseRole,
                       @NotNull List<String> permissions,
                       boolean enabled,
                       Integer expectedRevision) {}

    /** Stored custom role; {@code permissions} is comma-separated. */
    public record Row(String key, String name, String kind, String baseRole, String permissions, boolean enabled, int revision) {}

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

    private final JdbcClient db;
    private final Audit audit;
    private final Tokens tokens;

    public AccessRoleServiceV1_0(JdbcClient db, Audit audit, Tokens tokens) {
        this.db = db;
        this.audit = audit;
        this.tokens = tokens;
    }

    public List<Row> roles() { return db.sql("select * from access_role order by kind, name").query(Row.class).list(); }

    public List<String> catalog() { return Arrays.stream(Tenant.Perm.values()).map(Enum::name).toList(); }

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

    /**
     * Effective permissions of a member. Static so the request interceptors and token minting can call it without a bean.
     * A disabled or mismatched custom role falls back to VIEW only (fail closed), never to the full ceiling.
     */
    public static Set<Tenant.Perm> permissions(JdbcClient db, UUID org, UUID user, Tenant.Role base) {
        return db.sql("""
                select r.permissions from membership m join access_role r on r.key = m.custom_role_key
                join organization o on o.id = m.org_id
                where m.org_id = :o and m.user_id = :u and r.kind = o.kind and r.base_role = m.role and r.enabled""")
                .param("o", org).param("u", user).query(String.class).optional()
                .map(csv -> {
                    Set<Tenant.Perm> granted = new HashSet<>();
                    for (Tenant.Perm p : base.perms) if (Times.codes(csv).contains(p.name())) granted.add(p);
                    return Set.copyOf(granted);
                })
                .orElseGet(() -> {
                    boolean hasCustom = db.sql("select count(*) from membership where org_id = :o and user_id = :u and custom_role_key is not null")
                            .param("o", org).param("u", user).query(Integer.class).single() > 0;
                    return hasCustom ? Set.of(Tenant.Perm.VIEW) : Set.copyOf(base.perms);
                });
    }

    /** Platform admins create or update a custom role. Kind and ceiling are fixed once created. */
    @Transactional
    public void save(Role role) {
        if (!db.sql("select platform_admin from app_user where id = :u").param("u", Auth.userId()).query(Boolean.class).single())
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
        Tenant.Role base;
        try { base = Tenant.Role.valueOf(role.baseRole()); } catch (Exception e) { throw ApiException.bad("Unknown role ceiling."); }
        // OWNER stays a built-in recovery role; partner ceilings only for partner roles and vice versa.
        if (base == Tenant.Role.OWNER || base.partner() != role.kind().equals("PARTNER"))
            throw ApiException.bad("Choose a compatible role ceiling; owner remains a built-in recovery role.");
        Set<String> allowed = new HashSet<>(base.perms.stream().map(Enum::name).toList());
        if (!allowed.containsAll(role.permissions()) || !role.permissions().contains("VIEW"))
            throw ApiException.bad("Permissions must include VIEW and stay within the role ceiling.");

        Row old = roles().stream().filter(x -> x.key().equals(role.key())).findFirst().orElse(null);
        if (old != null && (!old.kind().equals(role.kind()) || !old.baseRole().equals(role.baseRole())))
            throw ApiException.bad("Organization type and role ceiling cannot change.");

        String permissions = String.join(",", role.permissions());
        if (old == null) {
            db.sql("insert into access_role(key, name, kind, base_role, permissions, enabled) values (:k, :n, :kind, :b, :p, :e)")
                    .param("k", role.key()).param("n", role.name()).param("kind", role.kind()).param("b", role.baseRole())
                    .param("p", permissions).param("e", role.enabled()).update();
        } else {
            int n = db.sql("update access_role set name = :n, permissions = :p, enabled = :e, revision = revision + 1 where key = :k and revision = :v")
                    .param("n", role.name()).param("p", permissions).param("e", role.enabled()).param("k", role.key())
                    .param("v", role.expectedRevision() == null ? -1 : role.expectedRevision()).update();
            if (n == 0) throw ApiException.conflict("STALE_CONFIGURATION", "Reload the role before saving.");
            // Members holding this role get fresh tokens so changed permissions apply at once.
            tokens.bump(db.sql("select distinct user_id from membership where custom_role_key = :k").param("k", role.key()).query(UUID.class).list());
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
