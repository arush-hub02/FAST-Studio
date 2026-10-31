package com.faststudio.v1_0.controllers.studio.team;

import com.faststudio.v1_0.core.auth.Accounts;
import com.faststudio.v1_0.core.auth.Auth;
import com.faststudio.v1_0.core.auth.Tokens;
import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.services.mail.EmailTemplates;
import com.faststudio.v1_0.services.mail.MailServiceV1_0;
import com.faststudio.v1_0.services.tenancy.AccessRoleServiceV1_0;


import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/** Organization members (tenant scope) and customer organizations (platform scope). */
@RestController
@RequestMapping("/api")
public class TeamControllerV1_0 {
    private final Data data;
    private final Audit audit;
    private final PasswordEncoder encoder;
    private final Accounts accounts;
    private final MailServiceV1_0 mail;
    private final Tokens tokens;
    private final AccessRoleServiceV1_0 accessRoles;

    public TeamControllerV1_0(Data data, Audit audit, PasswordEncoder encoder, Accounts accounts, MailServiceV1_0 mail, Tokens tokens,
                          AccessRoleServiceV1_0 accessRoles) {
        this.data = data;
        this.audit = audit;
        this.encoder = encoder;
        this.accounts = accounts;
        this.mail = mail;
        this.tokens = tokens;
        this.accessRoles=accessRoles;
    }

    private String name(UUID userId) {
        return data.db.sql("select name from app_user where id = :u").param("u", userId).query(String.class).single();
    }

    private String orgName(UUID orgId) {
        return data.db.sql("select name from organization where id = :o").param("o", orgId).query(String.class).single();
    }

    static String label(Tenant.Role r) {
        return r.name().charAt(0) + r.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Sign-in email for a new member or owner; returns the note shown to the admin who added them. */
    private String welcome(UUID orgId, UUID userId, String email, String temp, String inviter, Tenant.Role role, boolean newOrg) {
        Map<String, String> v = new HashMap<>(Map.of("name", name(userId), "email", email, "orgName", orgName(orgId),
                "inviterName", inviter, "role", label(role), "loginUrl", mail.appUrl() + "/login"));
        if (temp != null) v.put("temporaryPassword", temp);
        String template = temp == null ? "invite_existing_user" : newOrg ? "org_owner_welcome" : "invite_new_user";
        mail.send(orgId, template, email, v);
        if (temp == null) return mail.delivers() ? "Existing account added. We've emailed " + email + " to let them know." : "Existing account added to this organization.";
        return mail.delivers()
                ? "We've emailed the sign-in details to " + email + ". If it doesn't arrive, share this one-time password privately. They must choose their own password at first sign-in."
                : "No mail server is set up, so the email was saved to the outbox instead of being sent. Share this one-time password privately (not by group chat). They must choose their own password at first sign-in.";
    }

    private String resetNote(String email) {
        return mail.delivers()
                ? "We've emailed this link to " + email + ". You can also copy it here if the email doesn't arrive. It works once and expires in 24 hours; a new link cancels older ones."
                : "No mail server is set up, so send this link privately. It works once and expires in 24 hours. Creating a new link cancels older ones.";
    }

    private void resetEmail(UUID orgId, UUID userId, String email, Accounts.ResetLink link) {
        mail.send(orgId, "password_reset", email, Map.of("name", name(userId), "resetLink", mail.appUrl() + "/reset-password?token=" + link.token(),
                "expiresAt", EmailTemplates.when(link.expiresAt())));
    }

    public record Member(UUID userId, String name, String email, String role, OffsetDateTime createdAt, String customRoleKey) {}

    @GetMapping("/orgs/{org}/members")
    List<Member> members(Tenant t) {
        return data.db.sql("select u.id as user_id, u.name, u.email, m.role, m.created_at, m.custom_role_key from membership m join app_user u on u.id = m.user_id where m.org_id = :o order by m.role, u.name")
                .param("o", t.orgId()).query(Member.class).list();
    }

    public record InviteRequest(@NotBlank @Email String email, @NotBlank @Size(max = 80) String name, @NotBlank String role, String customRoleKey) {}

    public record InviteResult(Member member, String temporaryPassword, String note) {}

    @PostMapping("/orgs/{org}/members")
    @Transactional
    InviteResult invite(Tenant t, @Valid @RequestBody InviteRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Tenant.Role role = grant(t, r.role(), r.customRoleKey());
        ceiling(t, role);
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        UUID uid = data.db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).optional().orElse(null);
        String temp = null;
        if (uid == null) {
            temp = Accounts.temporaryPassword();
            uid = data.db.sql("insert into app_user(email, name, password_hash, must_change_password) values (:e,:n,:p,true) returning id")
                    .param("e", email).param("n", r.name().trim()).param("p", encoder.encode(temp)).query(UUID.class).single();
        }
        int exists = data.db.sql("select count(*) from membership where org_id = :o and user_id = :u").param("o", t.orgId()).param("u", uid).query(Integer.class).single();
        if (exists > 0) throw ApiException.conflict("ALREADY_MEMBER", email + " is already a member.");
        data.db.sql("insert into membership(org_id, user_id, role,custom_role_key) values (:o,:u,:r,:custom)").param("o", t.orgId()).param("u", uid).param("r", role.name()).param("custom",r.customRoleKey()).update();
        tokens.bump(List.of(uid));                 // their sign-in token gains this organization on its next refresh
        audit.log(t, "member.invite", "user", uid, email + " as " + role);
        Member m = members(t).stream().filter(x -> x.email().equals(email)).findFirst().orElseThrow();
        return new InviteResult(m, temp, welcome(t.orgId(), uid, email, temp, name(t.userId()), role, false));
    }

    public record ResetLinkResult(String path, OffsetDateTime expiresAt, String note) {}

    /**
     * Admin-issued password reset link (no email service): single use, expires in 24 h.
     * Org admins may only reset people who belong to this organization alone, because a password unlocks every
     * organization a person belongs to; anyone else (or a platform admin) is reset by the platform administrator.
     */
    @PostMapping("/orgs/{org}/members/{userId}/password-reset")
    ResetLinkResult resetMember(Tenant t, @PathVariable UUID userId) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        if (userId.equals(t.userId())) throw ApiException.conflict("SELF", "Use 'Change password' for your own account.");
        ceiling(t, currentRole(t, userId));
        int elsewhere = data.db.sql("select count(*) from membership where user_id = :u and org_id <> :o").param("u", userId).param("o", t.orgId()).query(Integer.class).single();
        boolean platform = data.db.sql("select platform_admin from app_user where id = :u").param("u", userId).query(Boolean.class).single();
        if (elsewhere > 0 || platform) throw new ApiException(HttpStatus.FORBIDDEN, "RESET_ELSEWHERE",
                "This person also has access outside this organization, so only the platform administrator can reset their password.");
        Accounts.ResetLink link = accounts.createReset(userId, t.userId());
        audit.log(t, "member.password_reset_link", "user", userId, "expires " + link.expiresAt());
        String email = data.db.sql("select email from app_user where id = :u").param("u", userId).query(String.class).single();
        resetEmail(t.orgId(), userId, email, link);
        return new ResetLinkResult("/reset-password?token=" + link.token(), link.expiresAt(), resetNote(email));
    }

    public record RoleRequest(@NotBlank String role, String customRoleKey) {}

    @PatchMapping("/orgs/{org}/members/{userId}")
    @Transactional
    Map<String, String> changeRole(Tenant t, @PathVariable UUID userId, @Valid @RequestBody RoleRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        Tenant.Role target = grant(t, r.role(), r.customRoleKey());
        Tenant.Role current = currentRole(t, userId);
        ceiling(t, target);
        ceiling(t, current);
        if (current == Tenant.Role.top(t.kind()) && (target != current || r.customRoleKey()!=null)) lastOwnerGuard(t);
        data.db.sql("update membership set role = :r,custom_role_key=:custom where org_id = :o and user_id = :u").param("r", target.name()).param("custom",r.customRoleKey()).param("o", t.orgId()).param("u", userId).update();
        tokens.bump(List.of(userId));
        audit.log(t, "member.role", "user", userId, current + " → " + target);
        return Map.of("role", target.name());
    }

    @DeleteMapping("/orgs/{org}/members/{userId}")
    @Transactional
    void remove(Tenant t, @PathVariable UUID userId) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        if (userId.equals(t.userId())) throw ApiException.conflict("SELF", "You can't remove yourself.");
        Tenant.Role current = currentRole(t, userId);
        ceiling(t, current);
        if (current == Tenant.Role.top(t.kind())) lastOwnerGuard(t);
        data.db.sql("delete from membership where org_id = :o and user_id = :u").param("o", t.orgId()).param("u", userId).update();
        tokens.bump(List.of(userId));
        audit.log(t, "member.remove", "user", userId, current.name());
    }

    private Tenant.Role currentRole(Tenant t, UUID userId) {
        return data.db.sql("select role from membership where org_id = :o and user_id = :u").param("o", t.orgId()).param("u", userId)
                .query(String.class).optional().map(Tenant.Role::valueOf).orElseThrow(() -> ApiException.notFound("Member"));
    }

    /** Nobody can grant or change a role above their own; only owners (partner admins) manage owners (partner admins). */
    private static void ceiling(Tenant t, Tenant.Role role) {
        if (role.rank > t.role().rank || (role == Tenant.Role.top(t.kind()) && t.role() != role))
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_CEILING", "You can't grant or change a role higher than your own.");
    }

    private void lastOwnerGuard(Tenant t) {
        Tenant.Role top = Tenant.Role.top(t.kind());
        data.db.sql("select id from organization where id=:o for update").param("o",t.orgId()).query(UUID.class).single();
        int owners = data.db.sql("select count(*) from membership where org_id = :o and role = :r and custom_role_key is null").param("o", t.orgId()).param("r", top.name()).query(Integer.class).single();
        if (owners <= 1) throw ApiException.conflict("LAST_OWNER", "An organization must keep at least one " + label(top).toLowerCase(Locale.ROOT) + ".");
    }

    /** Partner roles exist only in partner organizations, customer roles only in customer organizations. */
    static Tenant.Role role(Tenant t, String r) {
        Tenant.Role role;
        try { role = Tenant.Role.valueOf(r); } catch (Exception e) { throw ApiException.bad("Unknown role " + r); }
        if (role.partner() != t.partnerOrg()) throw ApiException.bad(label(role) + " is not a role in this kind of organization.");
        return role;
    }

    private Tenant.Role grant(Tenant t,String role,String custom) {
        if(custom!=null) {
            var definition=accessRoles.assignable(t,custom);
            if(!definition.baseRole().equals(role))throw ApiException.bad("The role ceiling does not match the selected role.");
            return role(t,role);
        }
        var target=role(t,role);
        if(!t.permissions().containsAll(target.perms))throw new ApiException(HttpStatus.FORBIDDEN,"ROLE_CEILING","You cannot grant permissions you do not hold.");
        return target;
    }

    // ------------------------------------------------------------------ platform administration

    record UserFlag(boolean platformAdmin) {}

    private void requirePlatform() {
        boolean admin = data.db.sql("select platform_admin from app_user where id = :id").param("id", Auth.userId()).query(Boolean.class).single();
        if (!admin) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Platform administrators only.");
    }

    public record OrgSummary(UUID id, String slug, String name, String status, String country, String timezone, String plan, int maxChannels,
                             long members, long channels, long liveChannels, long videos, long sessions, OffsetDateTime createdAt, String kind,
                             long partnerLinks) {}

    /** Platform view: usage and health counts only — no customer content, schedules or finance. */
    @GetMapping("/platform/orgs")
    List<OrgSummary> orgs() {
        requirePlatform();
        return data.db.sql("""
                select o.id, o.slug, o.name, o.status, o.country, o.timezone, o.plan, o.max_channels,
                  (select count(*) from membership m where m.org_id = o.id) as members,
                  (select count(*) from channel c where c.org_id = o.id and c.status <> 'ARCHIVED') as channels,
                  (select count(*) from channel c where c.org_id = o.id and c.status = 'LIVE') as live_channels,
                  (select count(*) from asset a where a.org_id = o.id and a.status <> 'ARCHIVED') as videos,
                  (select count(*) from playback_session s where s.org_id = o.id) as sessions, o.created_at, o.kind,
                  (select count(*) from partner_link l where (l.owner_org_id = o.id or l.partner_org_id = o.id) and l.status = 'ACTIVE') as partner_links
                from organization o where o.kind <> 'PLATFORM' order by o.kind, o.name""").query(OrgSummary.class).list();
    }

    public record CreateOrg(@NotBlank @Size(max = 80) String name, @NotBlank @Pattern(regexp = "[a-z0-9-]{3,40}") String slug,
                            @NotBlank @Pattern(regexp = "[A-Z]{2}") String country, @NotBlank String timezone,
                            @NotBlank @Email String ownerEmail, @NotBlank String ownerName,
                            @Pattern(regexp = "CUSTOMER|PARTNER") String kind) {}

    @PostMapping("/platform/orgs")
    @Transactional
    Map<String, Object> createOrg(@Valid @RequestBody CreateOrg r) {
        requirePlatform();
        try { java.time.ZoneId.of(r.timezone()); } catch(Exception e) { throw ApiException.bad("Unknown timezone."); }
        if (data.db.sql("select count(*) from organization where slug = :s").param("s", r.slug()).query(Integer.class).single() > 0)
            throw ApiException.conflict("SLUG_TAKEN", "That organization ID is taken.");
        String kind = r.kind() == null ? "CUSTOMER" : r.kind();
        Tenant.Role top = Tenant.Role.top(kind);
        UUID org = data.db.sql("insert into organization(slug, name, country, timezone, kind, max_channels) values (:s,:n,:c,:tz,:k,:max) returning id")
                .param("s", r.slug()).param("n", r.name()).param("c", r.country()).param("tz", r.timezone()).param("k", kind)
                .param("max", kind.equals("PARTNER") ? 0 : 10).query(UUID.class).single();
        String email = r.ownerEmail().toLowerCase(Locale.ROOT);
        UUID uid = data.db.sql("select id from app_user where email = :e").param("e", email).query(UUID.class).optional().orElse(null);
        String temp = null;
        if (uid == null) {
            temp = Accounts.temporaryPassword();
            uid = data.db.sql("insert into app_user(email, name, password_hash, must_change_password) values (:e,:n,:p,true) returning id")
                    .param("e", email).param("n", r.ownerName()).param("p", encoder.encode(temp)).query(UUID.class).single();
        }
        data.db.sql("insert into membership(org_id, user_id, role) values (:o,:u,:r)").param("o", org).param("u", uid).param("r", top.name()).update();
        tokens.bump(List.of(uid));
        // every customer org gets its own web player destination so it can watch its channels immediately
        if (kind.equals("CUSTOMER")) data.db.sql("insert into destination(org_id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour) values (:o,'Web player','OWNED_WEB','SSAI','DATERANGE',720)")
                .param("o", org).update();
        audit.log(org, Auth.userId(), "platform.org.create", "organization", org, r.name() + (kind.equals("PARTNER") ? " (partner)" : ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", org);
        out.put("ownerTemporaryPassword", temp);
        out.put("note", welcome(org, uid, email, temp, "The platform administrator", top, true));
        return out;
    }

    public record PlatformReset(@NotBlank @Email String email) {}

    /** Platform admin: reset link for any account (e.g. an organization owner who lost access). */
    @PostMapping("/platform/password-reset")
    ResetLinkResult platformReset(@Valid @RequestBody PlatformReset r) {
        requirePlatform();
        UUID uid = data.db.sql("select id from app_user where email = :e").param("e", r.email().trim().toLowerCase(Locale.ROOT))
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Account"));
        if (uid.equals(Auth.userId())) throw ApiException.conflict("SELF", "Use 'Change password' for your own account.");
        Accounts.ResetLink link = accounts.createReset(uid, Auth.userId());
        audit.platform(Auth.userId(), "platform.password_reset_link", "user", uid, r.email());
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        resetEmail(null, uid, email, link);
        return new ResetLinkResult("/reset-password?token=" + link.token(), link.expiresAt(), resetNote(email));
    }

    public record OrgStatus(@Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

    /** Suspension blocks the customer's admin access; data is kept and running channels keep playing (see MULTITENANCY.md). */
    @PostMapping("/platform/orgs/{id}/status")
    Map<String, String> orgStatus(@PathVariable UUID id, @Valid @RequestBody OrgStatus r) {
        requirePlatform();
        int n = data.db.sql("update organization set status = :s where id = :id and kind <> 'PLATFORM'").param("s", r.status()).param("id", id).update();
        if (n == 0) throw ApiException.notFound("Organization");
        tokens.bumpOrg(id);                        // members' tokens are re-checked at once
        audit.log(id, Auth.userId(), "platform.org." + r.status().toLowerCase(), "organization", id, "");
        return Map.of("status", r.status());
    }
}
