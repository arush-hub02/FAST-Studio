package com.faststudio.v1.repositories.media;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL for a project's Mux account (mux_account). Credentials arrive and leave sealed; this class never decrypts. */
@Repository
public class MuxAccountRepository {
    private final JdbcClient db;

    public MuxAccountRepository(JdbcClient db) { this.db = db; }

    /** Sealed credentials as stored. */
    public record Row(String tokenId, String tokenSecret, String webhookSecret, String environment, String passthroughTag) {}

    public Optional<Row> find(UUID org) {
        return db.sql("select token_id, token_secret, webhook_secret, environment, passthrough_tag from mux_account where org_id = :o")
                .param("o", org).query(Row.class).optional();
    }

    /** Creates or updates the account; a null sealed value keeps the stored one. */
    public void save(UUID org, String tokenId, String tokenSecret, String webhookSecret, String environment, String tag) {
        Map<String, Object> p = new HashMap<>(Map.of("o", org, "e", environment, "t", tag));
        p.put("i", tokenId);
        p.put("s", tokenSecret);
        p.put("w", webhookSecret);
        if (db.sql("""
                update mux_account set token_id = coalesce(:i, token_id), token_secret = coalesce(:s, token_secret), webhook_secret = coalesce(:w, webhook_secret),
                  environment = :e, passthrough_tag = :t, updated_at = now() where org_id = :o""").params(p).update() == 0)
            db.sql("insert into mux_account(org_id, token_id, token_secret, webhook_secret, environment, passthrough_tag) values (:o, :i, :s, :w, :e, :t)")
                    .params(p).update();
    }
}
