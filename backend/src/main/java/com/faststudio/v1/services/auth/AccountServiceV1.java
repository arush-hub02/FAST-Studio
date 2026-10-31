package com.faststudio.v1.services.auth;

import com.faststudio.v1.config.demo.DemoAccounts;
import com.faststudio.v1.core.auth.Accounts;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Feature;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.auth.AuthDto.*;
import com.faststudio.v1.repositories.auth.UserAccountRepository;
import com.faststudio.v1.services.mail.EmailTemplates;
import com.faststudio.v1.services.mail.MailServiceV1;
import com.faststudio.v1.core.auth.Permissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in person's account: who am I and where do I belong, changing my password, completing a reset link and
 * self-service "forgot password". Sign-in, cookies, sessions and token rotation stay in the auth controller because
 * they work on the HTTP request/response.
 */
@Service
public class AccountServiceV1 {
    private final UserAccountRepository repository;
    private final Accounts accounts;
    private final Permissions permissions;
    private final Tokens tokens;
    private final MailServiceV1 mail;
    private final Audit audit;
    private final boolean demo;

    public AccountServiceV1(UserAccountRepository repository, Accounts accounts, Permissions permissions, Tokens tokens, MailServiceV1 mail, Audit audit,
                            @Value("${faststudio.demo}") boolean demo) {
        this.repository = repository;
        this.accounts = accounts;
        this.permissions = permissions;
        this.tokens = tokens;
        this.mail = mail;
        this.audit = audit;
        this.demo = demo;
    }

    public boolean demoMode() { return demo; }

    /** The person with each membership's effective permissions, features and tenant. */
    public Me me(UUID id) {
        var u = repository.user(id);
        List<Membership> memberships = repository.memberships(id).stream()
                .map(m -> new Membership(m.orgId(), m.slug(), m.name(), m.status(), m.role(),
                        permissions.of(m.orgId(), id, Tenant.Role.valueOf(m.role())).stream().map(Enum::name).toList(),
                        m.kind(), Feature.parse(m.features()), m.tenantId(), m.tenantName()))
                .toList();
        return new Me(u.id(), u.name(), u.email(), u.platformAdmin(), memberships, demo, u.mustChangePassword());
    }

    /**
     * Changes the caller's own password (also clears a forced change after an admin-issued temporary password) and
     * signs out every other session; the controller re-issues the current session's cookies.
     */
    public void changePassword(UUID id, ChangePassword body) {
        var c = repository.credentials(id);
        if (!accounts.matches(body.currentPassword(), c.passwordHash()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BAD_CURRENT_PASSWORD", "Your current password is not correct.");
        if (body.newPassword().equals(body.currentPassword()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SAME_PASSWORD", "Choose a new password that is different from the current one.");
        accounts.requireStrong(body.newPassword(), c.email());
        accounts.setPassword(id, body.newPassword(), false);
        tokens.bump(List.of(id));
        tokens.revokeAll(id, Tokens.currentSid());                    // sign out everywhere else
        audit.log(null, id, "auth.password_change", "user", id, "");
        if (!c.mustChangePassword()) changedNotice(c.email(), c.name());   // not for the very first password
    }

    /** Completes a reset link (single use, 24 h) and closes every sign-in. */
    public Map<String, String> completeReset(ResetRequest body) {
        UUID id = accounts.consumeReset(body.token(), body.newPassword());
        tokens.bump(List.of(id));
        tokens.revokeAll(id, null);
        audit.log(null, id, "auth.password_reset", "user", id, "");
        var c = repository.credentials(id);
        changedNotice(c.email(), c.name());
        return Map.of("status", "PASSWORD_SET");
    }

    /**
     * Self-service reset for every account (any role, platform admins included). The answer is the same whether or not
     * the account exists, and each account gets at most 3 self-service links per hour. Without a mail server the email
     * waits in the outbox, so the answer says to ask an administrator.
     */
    public Map<String, String> forgot(ForgotRequest body) {
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        // The shared demo logins must keep the published demo password, or the one-click buttons stop working.
        if (demo && DemoAccounts.ALL.stream().anyMatch(a -> a.email().equals(email)))
            return Map.of("message", "Demo accounts can't be reset: their password is always " + DemoAccounts.PASSWORD + ".");
        repository.userByEmail(email).ifPresent(u -> {
            if (repository.recentSelfServiceResets(u.id()) >= 3) return;
            Accounts.ResetLink link = accounts.createReset(u.id(), null);
            mail.send(null, "password_reset", email, Map.of("name", u.name(), "resetLink", mail.appUrl() + "/reset-password?token=" + link.token(),
                    "expiresAt", EmailTemplates.when(link.expiresAt())));
            audit.log(null, u.id(), "auth.password_forgot", "user", u.id(), "");
        });
        String sent = "If an account exists for " + email + ", we've emailed it a reset link. The link works once, for 24 hours.";
        return Map.of("message", mail.delivers() ? sent
                : sent + " This server has no mail server set up yet, so the email is waiting in its outbox: ask your administrator to pass it on.");
    }

    private void changedNotice(String email, String name) {
        mail.send(null, "password_changed", email, Map.of("name", name, "changedAt", EmailTemplates.when(OffsetDateTime.now()), "loginUrl", mail.appUrl() + "/login"));
    }
}
