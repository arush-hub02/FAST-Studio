package com.faststudio.v1_0.controllers.platform.email;

import com.faststudio.v1_0.core.auth.Auth;
import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.services.mail.EmailTemplates;
import com.faststudio.v1_0.services.mail.MailServiceV1_0;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/** Platform administration of email: delivery status, editable templates with preview, and the sent-email log. */
@RestController
@RequestMapping("/api/platform/email")   // WebConfig: platform administrators only
public class EmailControllerV1_0 {
    private final EmailTemplates templates;
    private final MailServiceV1_0 mail;
    private final JdbcClient db;
    private final Audit audit;

    public EmailControllerV1_0(EmailTemplates templates, MailServiceV1_0 mail, JdbcClient db, Audit audit) {
        this.templates = templates;
        this.mail = mail;
        this.db = db;
        this.audit = audit;
    }

    @GetMapping("/status")
    MailServiceV1_0.Status status() {
        return mail.status();
    }

    @GetMapping("/templates")
    List<EmailTemplates.View> list() {
        return templates.list();
    }

    public record Draft(String subject, String body) {}

    public record Preview(String subject, String text, String html, List<String> problems) {}

    /** Renders an unsaved draft with example values, so people see the result while typing. */
    @PostMapping("/templates/{key}/preview")
    Preview preview(@PathVariable String key, @RequestBody Draft d) {
        String footer = key.equals(EmailTemplates.FOOTER) ? d.body()
                : templates.list().stream().filter(v -> v.key().equals(EmailTemplates.FOOTER)).findFirst().orElseThrow().body();
        String body = key.equals(EmailTemplates.FOOTER) ? "Hello Asha Rao,\n\nThis is how the footer looks under every email." : d.body();
        var r = EmailTemplates.render(d.subject() == null ? "" : d.subject(), body == null ? "" : body, footer == null ? "" : footer,
                EmailTemplates.sample(mail.appUrl()), mail.appUrl(), false);
        return new Preview(r.subject(), r.text(), r.html(), EmailTemplates.problems(key, d.subject(), d.body()));
    }

    @PutMapping("/templates/{key}")
    List<EmailTemplates.View> save(@PathVariable String key, @RequestBody Draft d) {
        templates.save(key, d.subject(), d.body(), Auth.userId());
        audit.platform(Auth.userId(), "platform.email_template.save", "email_template", key, "");
        return templates.list();
    }

    @DeleteMapping("/templates/{key}")
    List<EmailTemplates.View> reset(@PathVariable String key) {
        templates.reset(key);
        audit.platform(Auth.userId(), "platform.email_template.reset", "email_template", key, "");
        return templates.list();
    }

    public record TestRequest(@NotBlank @Email String to) {}

    @PostMapping("/test")
    Map<String, Object> test(@Valid @RequestBody TestRequest r) {
        String name = db.sql("select name from app_user where id = :id").param("id", Auth.userId()).query(String.class).single();
        UUID id = mail.send(null, "test_message", r.to().strip(), Map.of("name", name));
        return Map.of("id", id, "delivers", mail.delivers());
    }

    public record Message(UUID id, UUID orgId, String orgName, String template, String toEmail, String subject, String status, String detail,
                         int attempts, OffsetDateTime createdAt, OffsetDateTime sentAt, boolean canRetry) {}

    @GetMapping("/messages")
    List<Message> messages(@RequestParam(defaultValue = "200") int limit) {
        return db.sql("""
                select m.id, m.org_id, o.name as org_name, m.template, m.to_email, m.subject, m.status, m.detail, m.attempts, m.created_at, m.sent_at,
                  (m.status = 'FAILED' and m.pending_text is not null) as can_retry
                from email_message m left join organization o on o.id = m.org_id order by m.created_at desc limit :n""")
                .param("n", Math.min(Math.max(limit, 1), 1000)).query(Message.class).list();
    }

    public record MessageBody(String subject, String text, String html) {}

    /** The logged copy: one-time passwords and links are hidden. */
    @GetMapping("/messages/{id}")
    MessageBody message(@PathVariable UUID id) {
        return db.sql("select subject, body_text as text, body_html as html from email_message where id = :id").param("id", id)
                .query(MessageBody.class).optional().orElseThrow(() -> ApiException.notFound("Email"));
    }

    @PostMapping("/messages/{id}/retry")
    Map<String, Boolean> retry(@PathVariable UUID id) {
        if (!mail.retry(id)) throw ApiException.conflict("NOT_RETRYABLE", "Only failed emails that still have their content can be sent again.");
        return Map.of("queued", true);
    }
}
