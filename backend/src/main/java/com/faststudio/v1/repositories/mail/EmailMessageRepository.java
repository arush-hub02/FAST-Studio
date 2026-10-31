package com.faststudio.v1.repositories.mail;

import com.faststudio.v1.dto.mail.EmailDto.Message;
import com.faststudio.v1.dto.mail.EmailDto.MessageBody;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the email log and delivery queue (email_message). pending_* columns hold the unredacted text until it is sent. */
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

    /** Unredacted copy of a queued email, kept only until it is delivered. */
    public record Pending(String toEmail, String pendingSubject, String pendingText, String pendingHtml) {}

    /** shown* is the redacted copy kept in the log; pending* is what will actually be sent. */
    public UUID insertQueued(UUID org, String template, String to, String shownSubject, String shownText, String shownHtml,
                             String pendingSubject, String pendingText, String pendingHtml) {
        return db.sql("""
                insert into email_message(org_id, template, to_email, subject, body_text, body_html, pending_subject, pending_text, pending_html)
                values (:o,:t,:to,:s,:bt,:bh,:ps,:pt,:ph) returning id""")
                .param("o", org).param("t", template).param("to", to).param("s", shownSubject).param("bt", shownText)
                .param("bh", shownHtml).param("ps", pendingSubject).param("pt", pendingText).param("ph", pendingHtml)
                .query(UUID.class).single();
    }

    /** Re-queues a failed email that still has its text; false when it cannot be retried. */
    public boolean requeueFailed(UUID id) {
        return db.sql("update email_message set status = 'QUEUED', detail = null where id = :id and status = 'FAILED' and pending_text is not null")
                .param("id", id).update() > 0;
    }

    public List<UUID> queuedIds() {
        return db.sql("select id from email_message where status = 'QUEUED' order by created_at").query(UUID.class).list();
    }

    public Optional<Pending> pending(UUID id) {
        return db.sql("select to_email, pending_subject, pending_text, pending_html from email_message where id = :id and status = 'QUEUED'")
                .param("id", id).query(Pending.class).optional();
    }

    /** Records one delivery attempt; once done (not FAILED) the unredacted copy is erased. */
    public void recordAttempt(UUID id, String status, String detail, boolean done) {
        db.sql("""
                update email_message set status = :s, detail = :d, attempts = attempts + 1, sent_at = case when :done then now() end,
                  pending_subject = case when :done then null else pending_subject end,
                  pending_text = case when :done then null else pending_text end,
                  pending_html = case when :done then null else pending_html end
                where id = :id""")
                .param("s", status).param("d", detail).param("done", done).param("id", id).update();
    }

    public String userName(UUID userId) {
        return db.sql("select name from app_user where id = :id").param("id", userId).query(String.class).single();
    }
}
