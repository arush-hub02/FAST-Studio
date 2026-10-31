package com.faststudio.v1.repositories.mail;

import com.faststudio.v1.dto.mail.EmailDto.Message;
import com.faststudio.v1.dto.mail.EmailDto.MessageBody;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to the sent-email log for platform administrators. */
@Repository
public class EmailMessageRepository {
    private final JdbcClient db;

    public EmailMessageRepository(JdbcClient db) { this.db = db; }

    /** Newest first. */
    public List<Message> recent(int limit) {
        return db.sql("""
                select m.id, m.org_id, o.name as org_name, m.template, m.to_email, m.subject, m.status, m.detail, m.attempts, m.created_at, m.sent_at,
                  (m.status = 'FAILED' and m.pending_text is not null) as can_retry
                from email_message m left join organization o on o.id = m.org_id order by m.created_at desc limit :n""")
                .param("n", limit).query(Message.class).list();
    }

    public Optional<MessageBody> body(UUID id) {
        return db.sql("select subject, body_text as text, body_html as html from email_message where id = :id").param("id", id)
                .query(MessageBody.class).optional();
    }

    public String userName(UUID userId) {
        return db.sql("select name from app_user where id = :id").param("id", userId).query(String.class).single();
    }
}
