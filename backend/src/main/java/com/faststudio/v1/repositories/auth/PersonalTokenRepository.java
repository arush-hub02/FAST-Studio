package com.faststudio.v1.repositories.auth;

import com.faststudio.v1.dto.auth.AuthDto.PersonalToken;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for personal access tokens (personal_access_token). Tokens are looked up only by their SHA-256 hash. */
@Repository
public class PersonalTokenRepository {
    private final JdbcClient db;

    public PersonalTokenRepository(JdbcClient db) { this.db = db; }

    private static final String VIEW = """
            select p.id, p.name, p.token_hint, o.id as org_id, o.slug as org_slug, o.name as org_name, p.role_key,
                   p.created_at, p.expires_at, p.last_used_at
            from personal_access_token p left join organization o on o.id = p.org_id""";   // no org = platform scope

    /** The person's tokens that still work, newest first. */
    public List<PersonalToken> active(UUID user) {
        return db.sql(VIEW + " where p.user_id = :u and p.revoked_at is null and (p.expires_at is null or p.expires_at > now()) order by p.created_at desc")
                .param("u", user).query(PersonalToken.class).list();
    }

    public UUID insert(UUID user, UUID org, String name, String hash, String hint, String role, OffsetDateTime expiresAt) {
        return db.sql("""
                insert into personal_access_token(user_id, org_id, name, token_hash, token_hint, role_key, expires_at)
                values (:u, :o, :n, :h, :hint, :r, :e) returning id""")
                .param("u", user).param("o", org).param("n", name).param("h", hash).param("hint", hint).param("r", role).param("e", expiresAt)
                .query(UUID.class).single();
    }

    /** False when it isn't this person's token or was already revoked. */
    public boolean revoke(UUID user, UUID id) {
        return db.sql("update personal_access_token set revoked_at = now() where id = :id and user_id = :u and revoked_at is null")
                .param("id", id).param("u", user).update() > 0;
    }

    /** What a presented token is allowed to be, if it is valid now. orgId null = platform scope. */
    public record Usable(UUID id, UUID userId, UUID orgId, String roleKey) {}

    public Optional<Usable> usable(String hash) {
        return db.sql("""
                select id, user_id, org_id, role_key from personal_access_token
                where token_hash = :h and revoked_at is null and (expires_at is null or expires_at > now())""")
                .param("h", hash).query(Usable.class).optional();
    }

    public boolean isActive(UUID id) {
        return db.sql("select count(*) from personal_access_token where id = :id and revoked_at is null and (expires_at is null or expires_at > now())")
                .param("id", id).query(Integer.class).single() > 0;
    }

    /** At most once a minute per token, so busy scripts don't write on every call. */
    public void touch(UUID id) {
        db.sql("update personal_access_token set last_used_at = now() where id = :id and (last_used_at is null or last_used_at < now() - interval '1 minute')")
                .param("id", id).update();
    }
}
