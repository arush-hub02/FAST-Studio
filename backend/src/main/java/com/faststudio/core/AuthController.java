package com.faststudio.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import com.faststudio.mail.EmailTemplates;
import com.faststudio.mail.MailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepo;
    private final JdbcClient db;
    private final Audit audit;
    private final boolean demo;
    private final Accounts accounts;
    private final MailService mail;
    private final Tokens tokens;
    // ponytail: in-memory per-email throttle; move to a shared store when running >1 instance.
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepo, JdbcClient db,
                          Audit audit, @Value("${faststudio.demo}") boolean demo, Accounts accounts, MailService mail, Tokens tokens) {
        this.authManager = authManager;
        this.contextRepo = contextRepo;
        this.db = db;
        this.audit = audit;
        this.demo = demo;
        this.accounts = accounts;
        this.mail = mail;
        this.tokens = tokens;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record Membership(UUID orgId, String slug, String name, String status, String role, List<String> permissions, String kind) {}

    public record Me(UUID id, String name, String email, boolean platformAdmin, List<Membership> memberships, boolean demoMode,
                     boolean mustChangePassword) {}

    record UserRow(UUID id, String name, String email, boolean platformAdmin, boolean mustChangePassword) {}

    record MRow(UUID orgId, String slug, String name, String status, String role, String kind) {}

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());   // touching the token makes Spring set the XSRF-TOKEN cookie
    }

    /** Browser sign-in: tokens in HttpOnly cookies (or a server session in faststudio.auth.mode=session). */
    @PostMapping("/login")
    Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        var auth = authenticate(body);
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        UUID id = UUID.fromString(auth.getName());
        if (tokens.enabled()) tokens.signIn(id, res);
        else {
            req.getSession(true);
            req.changeSessionId();                    // session fixation protection
            contextRepo.saveContext(ctx, req, res);
        }
        audit.log(null, id, "auth.login", "user", id, "");
        return me();
    }

    /** Checks email + password, with a per-email throttle and the same answer whether or not the account exists. */
    private org.springframework.security.core.Authentication authenticate(LoginRequest body) {
        String key = body.email().toLowerCase(Locale.ROOT);
        Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            recent.removeIf(t -> t.isBefore(Instant.now().minusSeconds(300)));
            if (recent.size() >= 5) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many attempts. Wait a few minutes and try again.");
        }
        try {
            var auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
            failures.remove(key);
            return auth;
        } catch (AuthenticationException e) {
            synchronized (recent) { recent.add(Instant.now()); }
            // same message whether the account exists or not
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email or password is incorrect.");
        }
    }

    @GetMapping("/me")
    Me me() {
        UUID id = Auth.userId();
        UserRow u = db.sql("select id, name, email, platform_admin, must_change_password from app_user where id = :id").param("id", id)
                .query(UserRow.class).single();
        List<Membership> ms = db.sql("""
                        select o.id as org_id, o.slug, o.name, o.status, m.role, o.kind from membership m
                        join organization o on o.id = m.org_id where m.user_id = :id order by o.name""")
                .param("id", id).query(MRow.class).list().stream()
                .map(m -> new Membership(m.orgId(), m.slug(), m.name(), m.status(), m.role(),
                        Tenant.Role.valueOf(m.role()).perms.stream().map(Enum::name).toList(), m.kind()))
                .toList();
        return new Me(u.id(), u.name(), u.email(), u.platformAdmin(), ms, demo, u.mustChangePassword());
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    record Cred(String email, String name, String passwordHash, boolean mustChangePassword) {}

    /** Change your own password (also clears a forced change after an admin-issued temporary password). */
    @PostMapping("/password")
    Me changePassword(@Valid @RequestBody ChangePassword body, HttpServletRequest req, HttpServletResponse res) {
        UUID id = Auth.userId();
        Cred c = db.sql("select email, name, password_hash, must_change_password from app_user where id = :id").param("id", id).query(Cred.class).single();
        if (!accounts.matches(body.currentPassword(), c.passwordHash()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BAD_CURRENT_PASSWORD", "Your current password is not correct.");
        if (body.newPassword().equals(body.currentPassword()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SAME_PASSWORD", "Choose a new password that is different from the current one.");
        accounts.requireStrong(body.newPassword(), c.email());
        accounts.setPassword(id, body.newPassword(), false);
        tokens.bump(List.of(id));
        tokens.revokeAll(id, Tokens.currentSid());                    // sign out everywhere else
        if (tokens.enabled() && Tokens.currentSid() != null && Tokens.cookie(req, Tokens.ACCESS_COOKIE) != null) tokens.reissue(id, Tokens.currentSid(), res);
        audit.log(null, id, "auth.password_change", "user", id, "");
        if (!c.mustChangePassword()) changedNotice(c.email(), c.name());   // not for the very first password
        return me();
    }

    private void changedNotice(String email, String name) {
        mail.send(null, "password_changed", email, Map.of("name", name, "changedAt", EmailTemplates.when(OffsetDateTime.now()), "loginUrl", mail.appUrl() + "/login"));
    }

    public record ResetRequest(@NotBlank String token, @NotBlank String newPassword) {}

    /** Public: completes an admin-issued reset link (single use, expires after 24 h). */
    @PostMapping("/reset")
    Map<String, String> reset(@Valid @RequestBody ResetRequest body) {
        UUID id = accounts.consumeReset(body.token(), body.newPassword());
        tokens.bump(List.of(id));
        tokens.revokeAll(id, null);                                    // a reset closes every sign-in
        audit.log(null, id, "auth.password_reset", "user", id, "");
        Cred c = db.sql("select email, name, password_hash, must_change_password from app_user where id = :id").param("id", id).query(Cred.class).single();
        changedNotice(c.email(), c.name());
        return Map.of("status", "PASSWORD_SET");
    }

    public record ForgotRequest(@NotBlank @Email String email) {}

    record Named(UUID id, String name) {}

    /**
     * Public self-service reset for every account (any role, platform admins included). The answer is the same
     * whether or not the account exists, and each account gets at most 3 self-service links per hour. Without a mail
     * server the email waits in the outbox, so the answer says to ask an administrator.
     */
    @PostMapping("/forgot")
    Map<String, String> forgot(@Valid @RequestBody ForgotRequest body) {
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        // the shared demo logins must keep the published demo password, or the one-click buttons stop working
        if (demo && DemoAccounts.ALL.stream().anyMatch(a -> a.email().equals(email)))
            return Map.of("message", "Demo accounts can't be reset: their password is always " + DemoAccounts.PASSWORD + ".");
        db.sql("select id, name from app_user where email = :e").param("e", email).query(Named.class).optional().ifPresent(u -> {
            int recent = db.sql("select count(*) from password_reset where user_id = :u and created_by is null and created_at > now() - interval '1 hour'")
                    .param("u", u.id()).query(Integer.class).single();
            if (recent >= 3) return;
            Accounts.ResetLink link = accounts.createReset(u.id(), null);
            mail.send(null, "password_reset", email, Map.of("name", u.name(), "resetLink", mail.appUrl() + "/reset-password?token=" + link.token(),
                    "expiresAt", EmailTemplates.when(link.expiresAt())));
            audit.log(null, u.id(), "auth.password_forgot", "user", u.id(), "");
        });
        String sent = "If an account exists for " + email + ", we've emailed it a reset link. The link works once, for 24 hours.";
        return Map.of("message", mail.delivers() ? sent
                : sent + " This server has no mail server set up yet, so the email is waiting in its outbox: ask your administrator to pass it on.");
    }

    // ---------------------------------------------------------------- tokens (docs/PARTNER_ACCESS_AND_TOKENS.md §4.2)

    private void requireTokens() {
        if (!tokens.enabled()) throw ApiException.notFound("Token sign-in (this server uses faststudio.auth.mode=session)");
    }

    public record TokenResponse(String access_token, String token_type, long expires_in, String refresh_token, Long refresh_expires_in,
                                Map<String, Object> org) {}

    private static TokenResponse json(Tokens.Access a, Tokens.Refresh r) {
        long now = Instant.now().getEpochSecond();
        return new TokenResponse(a.token(), "Bearer", a.expiresAt().getEpochSecond() - now, r == null ? null : r.token(),
                r == null ? null : r.expiresAt().getEpochSecond() - now, a.org() == null ? null : Tokens.claim(a.org()));
    }

    /** For scripts and partner systems: email + password in the body, tokens in the answer (no cookies). */
    @PostMapping("/token")
    TokenResponse token(@Valid @RequestBody LoginRequest body) {
        requireTokens();
        UUID id = UUID.fromString(authenticate(body).getName());
        UUID sid = UUID.randomUUID();
        Tokens.Access a = tokens.mint(id, null, sid);
        Tokens.Refresh r = tokens.startFamily(id, sid, a.org() == null ? null : a.org().id());
        audit.log(null, id, "auth.token", "user", id, "");
        return json(a, r);
    }

    public record RefreshRequest(String refresh_token) {}

    /** New access token (and a new refresh token): from the browser's cookie, or refresh_token in the body. */
    @PostMapping("/token/refresh")
    TokenResponse refresh(@RequestBody(required = false) RefreshRequest body, HttpServletRequest req, HttpServletResponse res) {
        requireTokens();
        String fromBody = body == null ? null : body.refresh_token();
        Tokens.Renewed n = tokens.rotate(fromBody != null ? fromBody : Tokens.cookie(req, Tokens.REFRESH_COOKIE));
        Tokens.Access a = tokens.mint(n.userId(), n.activeOrg(), n.sid());
        if (fromBody != null) return json(a, n.refresh());
        tokens.setCookies(res, a, n.refresh());
        return json(new Tokens.Access("(in cookie)", a.expiresAt(), a.org()), null);
    }

    public record SwitchRequest(@NotBlank String org) {}

    /** Makes another of the person's organizations the token's active one ("org", "tid", "perms"). */
    @PostMapping("/token/switch")
    TokenResponse switchOrg(@Valid @RequestBody SwitchRequest body, HttpServletRequest req, HttpServletResponse res) {
        requireTokens();
        UUID id = Auth.userId(), sid = Tokens.currentSid();
        if (sid == null) throw ApiException.notFound("Sign-in");
        Tokens.OrgClaim target = tokens.orgs(id).stream().filter(o -> o.id().toString().equals(body.org()) || o.slug().equals(body.org()))
                .findFirst().orElseThrow(() -> ApiException.notFound("Organization"));
        tokens.setActiveOrg(sid, target.id());
        Tokens.Access a = tokens.mint(id, target.id(), sid);
        if (req.getHeader("Authorization") != null) return json(a, null);
        tokens.setCookies(res, a, null);
        return json(new Tokens.Access("(in cookie)", a.expiresAt(), a.org()), null);
    }

    /** Ends this sign-in (refresh cookie, or refresh_token in the body) and clears the cookies. Always succeeds. */
    @PostMapping("/logout")
    org.springframework.http.ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body, HttpServletRequest req, HttpServletResponse res) {
        tokens.revokeByToken(body != null && body.refresh_token() != null ? body.refresh_token() : Tokens.cookie(req, Tokens.REFRESH_COOKIE));
        tokens.clearCookies(res);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /** Signs this person out on every device: all refresh tokens revoked, all access tokens out of date at once. */
    @PostMapping("/logout-all")
    org.springframework.http.ResponseEntity<Void> logoutAll(HttpServletResponse res) {
        requireTokens();
        UUID id = Auth.userId();
        tokens.revokeAll(id, null);
        tokens.bump(List.of(id));
        tokens.clearCookies(res);
        audit.log(null, id, "auth.logout_all", "user", id, "");
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /** Public: what the sign-in page should offer. */
    @GetMapping("/options")
    Map<String, Object> options() {
        return Map.of("demo", demo, "passwordResetByEmail", mail.delivers(), "tokens", tokens.enabled());
    }

    public record DemoAccount(String email, String name, String role, String description) {}

    /** Development-only: lists seeded fictional accounts so presenters can switch roles. Disabled outside demo mode. */
    @GetMapping("/demo-accounts")
    Map<String, Object> demoAccounts() {
        if (!demo) return Map.of("enabled", false, "accounts", List.of());
        return Map.of("enabled", true, "password", DemoAccounts.PASSWORD, "accounts", DemoAccounts.ALL);
    }
}
