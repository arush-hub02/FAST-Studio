package com.faststudio.v1.repositories.mail;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SQL for edited email templates (email_template). Templates without a row use the built-in default text. */
@Repository
public class EmailTemplateRepository {
    private final JdbcClient db;

    public EmailTemplateRepository(JdbcClient db) { this.db = db; }

    /** One customized template; subject is null for templates without a subject line. */
    public record Saved(String key, String subject, String body, OffsetDateTime updatedAt) {}

    public List<Saved> saved() {
        return db.sql("select key, subject, body, updated_at from email_template").query(Saved.class).list();
    }

    public void save(String key, String subject, String body, UUID by) {
        db.sql("""
                insert into email_template(key, subject, body, updated_by) values (:k,:s,:b,:u)
                on conflict (key) do update set subject = excluded.subject, body = excluded.body, updated_by = excluded.updated_by, updated_at = now()""")
                .param("k", key).param("s", subject).param("b", body).param("u", by).update();
    }

    /** Back to the built-in default. */
    public void delete(String key) {
        db.sql("delete from email_template where key = :k").param("k", key).update();
    }
}
