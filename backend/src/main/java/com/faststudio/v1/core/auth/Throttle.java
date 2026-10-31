package com.faststudio.v1.core.auth;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.repositories.auth.AuthAttemptRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Rate limits for sign-in, token and password-reset requests, stored in the database so every server instance
 * shares them and a restart doesn't reset them. Per-email limits stop guessing one account's password; per-address
 * limits stop one machine trying many accounts (password spraying) or sending many reset emails.
 * The client address comes from {@link HttpServletRequest#getRemoteAddr()}: behind a proxy, Tomcat replaces it with
 * X-Forwarded-For only when the proxy is on a private network (server.forward-headers-strategy=native).
 */
@Component
public class Throttle {
    /** Failed sign-ins per account. */
    public static final Limit LOGIN_EMAIL = new Limit("login-email", 5, Duration.ofMinutes(5));
    /** Failed sign-ins per client address, across all accounts. */
    public static final Limit LOGIN_IP = new Limit("login-ip", 20, Duration.ofMinutes(15));
    /** Reset-link requests per client address (each account also gets at most 3 links an hour). */
    public static final Limit FORGOT_IP = new Limit("forgot-ip", 10, Duration.ofHours(1));
    /** Failed partner client-credential requests per client address. */
    public static final Limit CLIENT_IP = new Limit("client-ip", 20, Duration.ofMinutes(15));

    /** Unknown, revoked or expired personal access tokens per client address: logged for monitoring, never blocking. */
    public static final Limit TOKEN_IP = new Limit("token-ip", 20, Duration.ofMinutes(15));

    public record Limit(String name, int max, Duration window) {}

    private final AuthAttemptRepository attempts;

    public Throttle(AuthAttemptRepository attempts) { this.attempts = attempts; }

    /** Throws 429 when the bucket already reached its limit. */
    public void check(Limit limit, String key) {
        if (attempts.count(bucket(limit, key), limit.window()) >= limit.max())
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Wait a few minutes and try again.");
    }

    public void record(Limit limit, String key) { attempts.add(bucket(limit, key)); }

    /** After a successful sign-in the account's failure count starts again. */
    public void clear(Limit limit, String key) { attempts.clear(bucket(limit, key)); }

    /** Email keys are hashed so the table holds no personal data. */
    public static String email(String email) { return Accounts.sha256(email.strip().toLowerCase(Locale.ROOT)); }

    public static String ip(HttpServletRequest req) { return req.getRemoteAddr(); }

    private static String bucket(Limit limit, String key) { return limit.name() + ":" + key; }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void purge() { attempts.deleteOlderThan(Duration.ofDays(1)); }
}
