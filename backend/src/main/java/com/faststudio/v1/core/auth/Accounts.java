package com.faststudio.v1.core.auth;

import com.faststudio.v1.config.demo.DemoAccounts;
import com.faststudio.v1.core.common.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Account lifecycle: password rules, password changes, and reset links (admin-issued or self-service by email) (random 256-bit token, stored only as a SHA-256 hash, single use, 24 h).
 */
@Component
public class Accounts {
    public static final Duration RESET_TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final PasswordEncoder encoder;

    public Accounts(JdbcClient db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    /** Minimum rules: 10+ characters, not the email, not a known demo password. Returns a reason or null. */
    public static String weakness(String password, String email) {
        if (password == null || password.length() < 10) return "Use at least 10 characters.";
        if (password.length() > 200) return "That password is too long.";
        String p = password.toLowerCase(Locale.ROOT);
        if (email != null && p.contains(email.toLowerCase(Locale.ROOT).split("@")[0]) && email.split("@")[0].length() >= 4)
            return "Don't use your email name in the password.";
        if (p.equals(DemoAccounts.PASSWORD) || p.equals("password1234") || p.chars().distinct().count() < 4)
            return "That password is too easy to guess.";
        return null;
    }

    public void requireStrong(String password, String email) {
        String why = weakness(password, email);
        if (why != null) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "WEAK_PASSWORD", why);
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }

    public void setPassword(UUID userId, String newPassword, boolean mustChange) {
        db.sql("update app_user set password_hash = :h, must_change_password = :m, password_changed_at = now() where id = :id")
                .param("h", encoder.encode(newPassword)).param("m", mustChange).param("id", userId).update();
    }

    /** One-time password for new accounts created by an admin; the user must change it on first sign-in. */
    public static String temporaryPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    public record ResetLink(String token, OffsetDateTime expiresAt) {}

    /** Creates a reset link for a user; any earlier unused links for that user stop working. */
    @Transactional
    public ResetLink createReset(UUID userId, UUID createdBy) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.sql("update password_reset set used_at = now() where user_id = :u and used_at is null").param("u", userId).update();
        OffsetDateTime expires = OffsetDateTime.now().plus(RESET_TTL);
        db.sql("insert into password_reset(token_hash, user_id, created_by, expires_at) values (:h,:u,:c,:e)")
                .param("h", sha256(token)).param("u", userId).param("c", createdBy).param("e", expires).update();
        return new ResetLink(token, expires);
    }

    record ResetRow(UUID userId, String email) {}

    /** Consumes a reset link: valid, unused and unexpired, exactly once. Returns the user id. */
    @Transactional
    public UUID consumeReset(String token, String newPassword) {
        ResetRow r = db.sql("""
                select r.user_id, u.email from password_reset r join app_user u on u.id = r.user_id
                where r.token_hash = :h and r.used_at is null and r.expires_at > now() for update of r""")
                .param("h", sha256(token == null ? "" : token)).query(ResetRow.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "RESET_INVALID", "This reset link is invalid, already used or expired. Ask an administrator for a new one."));
        requireStrong(newPassword, r.email());
        db.sql("update password_reset set used_at = now() where user_id = :u and used_at is null").param("u", r.userId()).update();
        setPassword(r.userId(), newPassword, false);
        return r.userId();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
