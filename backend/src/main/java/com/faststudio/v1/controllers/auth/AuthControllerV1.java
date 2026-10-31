package com.faststudio.v1.controllers.auth;

import com.faststudio.v1.config.demo.DemoAccounts;
import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.auth.Throttle;
import com.faststudio.v1.core.auth.Tokens;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.dto.auth.AuthDto.*;
import com.faststudio.v1.services.auth.AccountServiceV1;
import com.faststudio.v1.services.mail.MailServiceV1;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Sign-in and sessions: browser sign-in (HttpOnly token cookies, or a server session in faststudio.auth.mode=session),
 * token sign-in for scripts, refresh rotation, switching the active organization, and sign-out. This controller owns
 * the HTTP/cookie/security-context mechanics; account data and password rules live in {@link AccountServiceV1}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthControllerV1 {
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepo;
    private final AccountServiceV1 accounts;
    private final Tokens tokens;
    private final MailServiceV1 mail;
    private final Audit audit;
    private final Throttle throttle;

    public AuthControllerV1(AuthenticationManager authManager, SecurityContextRepository contextRepo, AccountServiceV1 accounts,
                            Tokens tokens, MailServiceV1 mail, Audit audit, Throttle throttle) {
        this.authManager = authManager;
        this.contextRepo = contextRepo;
        this.accounts = accounts;
        this.tokens = tokens;
        this.mail = mail;
        this.audit = audit;
        this.throttle = throttle;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());   // touching the token makes Spring set the XSRF-TOKEN cookie
    }

    /** Browser sign-in: tokens in HttpOnly cookies (or a server session in faststudio.auth.mode=session). */
    @PostMapping("/login")
    Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        var auth = authenticate(body, req);
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
        return accounts.me(id);
    }

    /**
     * Checks email + password with the same answer whether or not the account exists. Limited per account and per
     * client address (see {@link Throttle}); only failures count, and a success clears the account's count.
     */
    private Authentication authenticate(LoginRequest body, HttpServletRequest req) {
        String email = Throttle.email(body.email()), ip = Throttle.ip(req);
        throttle.check(Throttle.LOGIN_EMAIL, email);
        throttle.check(Throttle.LOGIN_IP, ip);
        try {
            var auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
            throttle.clear(Throttle.LOGIN_EMAIL, email);
            return auth;
        } catch (AuthenticationException e) {
            throttle.record(Throttle.LOGIN_EMAIL, email);
            throttle.record(Throttle.LOGIN_IP, ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email or password is incorrect.");
        }
    }

    @GetMapping("/me")
    Me me() { return accounts.me(Auth.userId()); }

    /** Change your own password; this session's cookies are re-issued so it stays signed in. */
    @PostMapping("/password")
    Me changePassword(@Valid @RequestBody ChangePassword body, HttpServletRequest req, HttpServletResponse res) {
        UUID id = Auth.userId();
        accounts.changePassword(id, body);
        if (tokens.enabled() && Tokens.currentSid() != null && Tokens.cookie(req, Tokens.ACCESS_COOKIE) != null) tokens.reissue(id, Tokens.currentSid(), res);
        return accounts.me(id);
    }

    /** Public: completes a reset link (single use, expires after 24 h). */
    @PostMapping("/reset")
    Map<String, String> reset(@Valid @RequestBody ResetRequest body) { return accounts.completeReset(body); }

    /** Public: self-service reset link by email (same answer whether or not the account exists). */
    @PostMapping("/forgot")
    Map<String, String> forgot(@Valid @RequestBody ForgotRequest body, HttpServletRequest req) {
        throttle.check(Throttle.FORGOT_IP, Throttle.ip(req));
        throttle.record(Throttle.FORGOT_IP, Throttle.ip(req));
        return accounts.forgot(body);
    }

    // ---------------------------------------------------------------- tokens (docs/PARTNER_ACCESS_AND_TOKENS.md §4.2)

    private void requireTokens() {
        if (!tokens.enabled()) throw ApiException.notFound("Token sign-in (this server uses faststudio.auth.mode=session)");
    }

    /** OAuth-style token answer; field names follow RFC 6749. */
    public record TokenResponse(String access_token, String token_type, long expires_in, String refresh_token, Long refresh_expires_in,
                                Map<String, Object> org) {}

    private static TokenResponse json(Tokens.Access a, Tokens.Refresh r) {
        long now = Instant.now().getEpochSecond();
        return new TokenResponse(a.token(), "Bearer", a.expiresAt().getEpochSecond() - now, r == null ? null : r.token(),
                r == null ? null : r.expiresAt().getEpochSecond() - now, a.org() == null ? null : Tokens.claim(a.org()));
    }

    /** For scripts and partner systems: email + password in the body, tokens in the answer (no cookies). */
    @PostMapping("/token")
    TokenResponse token(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
        requireTokens();
        UUID id = UUID.fromString(authenticate(body, req).getName());
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
    ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body, HttpServletRequest req, HttpServletResponse res) {
        tokens.revokeByToken(body != null && body.refresh_token() != null ? body.refresh_token() : Tokens.cookie(req, Tokens.REFRESH_COOKIE));
        tokens.clearCookies(res);
        return ResponseEntity.noContent().build();
    }

    /** Signs this person out on every device: all refresh tokens revoked, all access tokens out of date at once. */
    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(HttpServletResponse res) {
        requireTokens();
        UUID id = Auth.userId();
        tokens.revokeAll(id, null);
        tokens.bump(List.of(id));
        tokens.clearCookies(res);
        audit.log(null, id, "auth.logout_all", "user", id, "");
        return ResponseEntity.noContent().build();
    }

    /** Public: what the sign-in page should offer. */
    @GetMapping("/options")
    Map<String, Object> options() {
        return Map.of("demo", accounts.demoMode(), "passwordResetByEmail", mail.delivers(), "tokens", tokens.enabled());
    }

    /** Development-only: lists seeded fictional accounts so presenters can switch roles. Disabled outside demo mode. */
    @GetMapping("/demo-accounts")
    Map<String, Object> demoAccounts() {
        if (!accounts.demoMode()) return Map.of("enabled", false, "accounts", List.of());
        return Map.of("enabled", true, "password", DemoAccounts.PASSWORD, "accounts", DemoAccounts.ALL);
    }
}
