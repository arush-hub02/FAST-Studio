package com.faststudio.v1.services.mail;

import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.mail.EmailDto.Draft;
import com.faststudio.v1.dto.mail.EmailDto.Preview;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * A project's own wording of the emails sent about it (P5). Without its own version a project uses the platform's,
 * and "reset" goes back to that. Owners and administrators (Manage team) edit; everyone in the project may read.
 */
@Service
public class ProjectEmailServiceV1 {
    private final EmailTemplates templates;
    private final MailServiceV1 mail;
    private final Audit audit;

    public ProjectEmailServiceV1(EmailTemplates templates, MailServiceV1 mail, Audit audit) {
        this.templates = templates;
        this.mail = mail;
        this.audit = audit;
    }

    public List<EmailTemplates.View> list(Tenant t) {
        t.require(Tenant.Perm.VIEW);
        return views(t.orgId());
    }

    /** The draft with example values and the project's footer, and what would stop it saving. */
    public Preview preview(Tenant t, String key, Draft d) {
        t.require(Tenant.Perm.VIEW);   // a preview changes nothing; read-only members see it too
        boolean footerTemplate = key.equals(EmailTemplates.FOOTER);
        if (!EmailTemplates.PROJECT_KEYS.contains(key)) throw com.faststudio.v1.core.common.ApiException.notFound("Email template");
        String footer = footerTemplate ? d.body()
                : views(t.orgId()).stream().filter(v -> v.key().equals(EmailTemplates.FOOTER)).findFirst().orElseThrow().body();
        String body = footerTemplate ? "Hello Asha Rao,\n\nThis is how the footer looks under every email." : d.body();
        var r = EmailTemplates.render(d.subject() == null ? "" : d.subject(), body == null ? "" : body, footer == null ? "" : footer,
                EmailTemplates.sample(mail.appUrl()), mail.appUrl(), false);
        return new Preview(r.subject(), r.text(), r.html(), EmailTemplates.problems(key, d.subject(), d.body()));
    }

    public List<EmailTemplates.View> save(Tenant t, String key, Draft d) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        templates.saveProject(t.orgId(), key, d.subject(), d.body(), t.userId());
        audit.log(t, "email_template.save", "email_template", null, key);
        return views(t.orgId());
    }

    /** Back to the platform's version. */
    public List<EmailTemplates.View> reset(Tenant t, String key) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        templates.resetProject(t.orgId(), key);
        audit.log(t, "email_template.reset", "email_template", null, key);
        return views(t.orgId());
    }

    /** Only the emails a project may word itself; the platform's templates are read as the system. */
    private List<EmailTemplates.View> views(UUID org) {
        return TenantScope.as(org, () -> templates.list(org)).stream().filter(v -> EmailTemplates.PROJECT_KEYS.contains(v.key())).toList();
    }
}
