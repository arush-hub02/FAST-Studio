package com.faststudio.v1_0.services.team;

import com.faststudio.v1_0.core.auth.Accounts;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.repositories.team.MembershipRepository;
import com.faststudio.v1_0.services.mail.EmailTemplates;
import com.faststudio.v1_0.services.mail.MailServiceV1_0;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Account emails shared by team management and platform organization administration:
 * welcome / invitation emails and password reset links. Each method returns the note shown to the admin.
 */
@Service
public class AccountEmailService {
    private final MailServiceV1_0 mail;
    private final MembershipRepository repository;

    public AccountEmailService(MailServiceV1_0 mail, MembershipRepository repository) {
        this.mail = mail;
        this.repository = repository;
    }

    /** "Content manager" style label for a built-in role. */
    public static String label(Tenant.Role r) {
        return r.name().charAt(0) + r.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * Sends the sign-in email to a new member or owner. {@code temporaryPassword} is null for an existing account.
     * {@code newOrg} selects the owner-welcome template for a brand-new organization.
     */
    public String welcome(UUID orgId, UUID userId, String email, String temporaryPassword, String inviter, Tenant.Role role, boolean newOrg) {
        Map<String, String> v = new HashMap<>(Map.of("name", repository.userName(userId), "email", email,
                "orgName", repository.organizationName(orgId), "inviterName", inviter, "role", label(role),
                "loginUrl", mail.appUrl() + "/login"));
        if (temporaryPassword != null) v.put("temporaryPassword", temporaryPassword);
        String template = temporaryPassword == null ? "invite_existing_user" : newOrg ? "org_owner_welcome" : "invite_new_user";
        mail.send(orgId, template, email, v);
        if (temporaryPassword == null)
            return mail.delivers() ? "Existing account added. We've emailed " + email + " to let them know." : "Existing account added to this organization.";
        return mail.delivers()
                ? "We've emailed the sign-in details to " + email + ". If it doesn't arrive, share this one-time password privately. They must choose their own password at first sign-in."
                : "No mail server is set up, so the email was saved to the outbox instead of being sent. Share this one-time password privately (not by group chat). They must choose their own password at first sign-in.";
    }

    /** Emails a password reset link (orgId may be null for platform-issued links) and returns the admin note. */
    public String passwordReset(UUID orgId, UUID userId, String email, Accounts.ResetLink link) {
        mail.send(orgId, "password_reset", email, Map.of("name", repository.userName(userId),
                "resetLink", mail.appUrl() + "/reset-password?token=" + link.token(),
                "expiresAt", EmailTemplates.when(link.expiresAt())));
        return mail.delivers()
                ? "We've emailed this link to " + email + ". You can also copy it here if the email doesn't arrive. It works once and expires in 24 hours; a new link cancels older ones."
                : "No mail server is set up, so send this link privately. It works once and expires in 24 hours. Creating a new link cancels older ones.";
    }
}
