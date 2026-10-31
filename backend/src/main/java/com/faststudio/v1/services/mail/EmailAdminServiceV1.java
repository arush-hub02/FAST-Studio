package com.faststudio.v1.services.mail;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.dto.mail.EmailDto.*;
import com.faststudio.v1.repositories.mail.EmailMessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform email administration: delivery status, editable templates with live preview, test sends and the sent log. */
@Service
public class EmailAdminServiceV1 {
    private final EmailTemplates templates;
    private final MailServiceV1 mail;
    private final EmailMessageRepository messages;
    private final Audit audit;

    public EmailAdminServiceV1(EmailTemplates templates, MailServiceV1 mail, EmailMessageRepository messages, Audit audit) {
        this.templates = templates;
        this.mail = mail;
        this.messages = messages;
        this.audit = audit;
    }

    public MailServiceV1.Status status() { return mail.status(); }

    public List<EmailTemplates.View> templates() { return templates.list(); }

    /** Renders an unsaved draft with example values, so people see the result while typing. */
    public Preview preview(String key, Draft d) {
        boolean footerTemplate = key.equals(EmailTemplates.FOOTER);
        String footer = footerTemplate ? d.body()
                : templates.list().stream().filter(v -> v.key().equals(EmailTemplates.FOOTER)).findFirst().orElseThrow().body();
        String body = footerTemplate ? "Hello Asha Rao,\n\nThis is how the footer looks under every email." : d.body();
        var r = EmailTemplates.render(d.subject() == null ? "" : d.subject(), body == null ? "" : body, footer == null ? "" : footer,
                EmailTemplates.sample(mail.appUrl()), mail.appUrl(), false);
        return new Preview(r.subject(), r.text(), r.html(), EmailTemplates.problems(key, d.subject(), d.body()));
    }

    public List<EmailTemplates.View> save(String key, Draft d) {
        templates.save(key, d.subject(), d.body(), Auth.userId());
        audit.platform(Auth.userId(), "platform.email_template.save", "email_template", key, "");
        return templates.list();
    }

    /** Back to the built-in text. */
    public List<EmailTemplates.View> reset(String key) {
        templates.reset(key);
        audit.platform(Auth.userId(), "platform.email_template.reset", "email_template", key, "");
        return templates.list();
    }

    public Map<String, Object> sendTest(TestRequest r) {
        UUID id = mail.send(null, "test_message", r.to().strip(), Map.of("name", messages.userName(Auth.userId())));
        return Map.of("id", id, "delivers", mail.delivers());
    }

    public List<Message> messages(int limit) { return messages.recent(Math.min(Math.max(limit, 1), 1000)); }

    public MessageBody message(UUID id) { return messages.body(id).orElseThrow(() -> ApiException.notFound("Email")); }

    public Map<String, Boolean> retry(UUID id) {
        if (!mail.retry(id)) throw ApiException.conflict("NOT_RETRYABLE", "Only failed emails that still have their content can be sent again.");
        return Map.of("queued", true);
    }
}
