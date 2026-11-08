package com.faststudio.v1.services.team;

import com.faststudio.v1.core.auth.Accounts;
import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.team.MembershipRepository;
import com.faststudio.v1.repositories.team.TenantRoleRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tenant-wide roles (platform administrators): one built-in customer role for a person in every project of a tenant,
 * including projects added later. The role reaches each project as a membership marked via_tenant, so every existing
 * permission check applies unchanged. A role the person has directly in a project wins and is never overwritten.
 * Each project keeps at least one plain owner.
 */
@Service
public class TenantRoleServiceV1 {
    private final TenantRoleRepository repository;
    private final MembershipRepository members;
    private final AccountEmailService emails;
    private final PasswordEncoder encoder;
    private final Tokens tokens;
    private final Audit audit;
    private final TransactionTemplate tx;

    public TenantRoleServiceV1(TenantRoleRepository repository, MembershipRepository members, AccountEmailService emails, PasswordEncoder encoder,
                               Tokens tokens, Audit audit, TransactionTemplate tx) {
        this.repository = repository;
        this.members = members;
        this.emails = emails;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
        this.tx = tx;
    }

    public record AddRequest(@NotBlank @Email String email, @NotBlank @Size(max = 80) String name, @NotBlank String role) {}

    public record RoleRequest(@NotBlank String role) {}

    public record AddResult(List<TenantRoleRepository.Row> members, String temporaryPassword, String note) {}

    public List<TenantRoleRepository.Row> list(UUID tenant) {
        return system(() -> {
            requireTenant(tenant);
            return repository.list(tenant);
        });
    }

    /** Gives a person (a new account when the email is new) a role in every project of the tenant. */
    public AddResult add(UUID tenant, AddRequest r) {
        Tenant.Role role = role(r.role());
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        String[] temporary = {null};
        UUID user = system(() -> tx.execute(s -> {
            requireTenant(tenant);
            UUID id = members.userByEmail(email).orElse(null);
            if (id == null) {
                temporary[0] = Accounts.temporaryPassword();
                id = members.createUser(email, r.name().trim(), encoder.encode(temporary[0]));
            }
            if (repository.role(tenant, id).isPresent()) throw ApiException.conflict("ALREADY_MEMBER", email + " already has a tenant-wide role.");
            repository.save(tenant, id, role.name());
            repository.spread(tenant, id, role.name());
            tokens.bump(List.of(id));
            audit.platform(Auth.userId(), "tenant.role.add", "user", id, email + " as " + role);
            return id;
        }));
        String note = system(() -> repository.firstProject(tenant)
                .map(p -> emails.welcome(p, user, email, temporary[0], "The platform administrator", role, false))
                .orElse("The tenant has no projects yet; the role applies to each project when it is added."));
        return new AddResult(list(tenant), temporary[0], note);
    }

    public List<TenantRoleRepository.Row> change(UUID tenant, UUID user, RoleRequest r) {
        Tenant.Role role = role(r.role());
        system(() -> tx.execute(s -> {
            String current = repository.role(tenant, user).orElseThrow(() -> ApiException.notFound("Tenant-wide role"));
            if (current.equals("OWNER") && role != Tenant.Role.OWNER) lastOwnerGuard(tenant, user);
            repository.save(tenant, user, role.name());
            repository.spread(tenant, user, role.name());
            tokens.bump(List.of(user));
            audit.platform(Auth.userId(), "tenant.role.change", "user", user, current + " → " + role);
            return null;
        }));
        return list(tenant);
    }

    /** Takes the tenant-wide role away; the person's own project roles stay. */
    public List<TenantRoleRepository.Row> remove(UUID tenant, UUID user) {
        system(() -> tx.execute(s -> {
            String current = repository.role(tenant, user).orElseThrow(() -> ApiException.notFound("Tenant-wide role"));
            if (current.equals("OWNER")) lastOwnerGuard(tenant, user);
            repository.withdraw(tenant, user);
            repository.delete(tenant, user);
            tokens.bump(List.of(user));
            audit.platform(Auth.userId(), "tenant.role.remove", "user", user, current);
            return null;
        }));
        return list(tenant);
    }

    /** Called when a project is added to a tenant: it gets every tenant-wide role. Runs inside the caller's transaction. */
    public void projectAdded(UUID tenant, UUID project) {
        List<UUID> gained = repository.spreadToProject(tenant, project);
        if (!gained.isEmpty()) tokens.bump(gained);
    }

    /** Refuses a project-level change of a membership that comes from a tenant-wide role (409 TENANT_WIDE). */
    public void requireProjectManaged(UUID org, UUID user) {
        if (system(() -> repository.viaTenant(org, user)))
            throw ApiException.conflict("TENANT_WIDE", "This person's role here comes from their tenant-wide role. Change it on the tenant.");
    }

    private void lastOwnerGuard(UUID tenant, UUID user) {
        List<String> orphaned = repository.projectsOwnedOnlyThroughTenant(tenant, user);
        if (!orphaned.isEmpty()) throw ApiException.conflict("LAST_OWNER",
                "These projects would have no owner: " + String.join(", ", orphaned) + ". Make someone else an owner there first.");
    }

    /** Tenant-wide roles are the built-in customer roles. */
    private static Tenant.Role role(String name) {
        Tenant.Role role;
        try { role = Tenant.Role.valueOf(name); } catch (Exception e) { throw ApiException.bad("Unknown role " + name); }
        if (role.partner()) throw ApiException.bad(AccountEmailService.label(role) + " is not a role in a customer tenant.");
        return role;
    }

    private void requireTenant(UUID tenant) {
        if (!repository.tenantExists(tenant)) throw ApiException.notFound("Tenant");
    }

    private static <T> T system(Supplier<T> work) { return TenantScope.bypass(work); }
}
