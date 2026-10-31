package com.faststudio.v1.services.mail;

import com.faststudio.v1.core.common.ApiException;

import org.springframework.http.HttpStatus;
import com.faststudio.v1.repositories.mail.EmailTemplateRepository;
import com.faststudio.v1.repositories.mail.EmailTemplateRepository.Saved;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every email the product sends. Each template is plain text with {{placeholders}}, so non-technical people can
 * edit it safely: values and text are HTML-escaped, a blank line starts a new paragraph, and a link on a line of
 * its own becomes a button. Platform admins override the built-in defaults (table email_template).
 */
@Component
public class EmailTemplates {
    public static final String FOOTER = "footer";
    /** One-time secrets: real value in the delivered email, ●●●●●● in the "Sent emails" log. */
    static final Set<String> SECRETS = Set.of("temporaryPassword", "resetLink");
    static final String HIDDEN = "●●●●●● (hidden in the log)";

    public record Def(String key, String name, String description, List<String> vars, List<String> required, String subject, String body) {}

    static final List<Def> DEFAULTS = List.of(
            new Def("invite_new_user", "Invitation — new person",
                    "Sent when someone without an account is invited to an organization. Contains their one-time password.",
                    List.of("name", "email", "orgName", "inviterName", "role", "temporaryPassword", "loginUrl"), List.of("temporaryPassword"),
                    "{{inviterName}} invited you to {{orgName}} on FAST Studio", """
                    Hello {{name}},

                    {{inviterName}} has invited you to join {{orgName}} on FAST Studio as {{role}}.

                    Sign in with:
                    Email: {{email}}
                    One-time password: {{temporaryPassword}}

                    {{loginUrl}}

                    You will be asked to choose your own password straight away. If you weren't expecting this invitation, you can ignore this email."""),
            new Def("invite_existing_user", "Added to an organization",
                    "Sent when someone who already has an account is added to another organization, or made owner of a new one.",
                    List.of("name", "orgName", "inviterName", "role", "loginUrl"), List.of(),
                    "You now have access to {{orgName}}", """
                    Hello {{name}},

                    {{inviterName}} added you to {{orgName}} on FAST Studio as {{role}}. Sign in with your usual password and choose {{orgName}} in the organization menu at the top.

                    {{loginUrl}}"""),
            new Def("org_owner_welcome", "New organization — owner welcome",
                    "Sent to the first owner when the platform administrator creates a new customer organization.",
                    List.of("name", "email", "orgName", "temporaryPassword", "loginUrl"), List.of("temporaryPassword"),
                    "Your FAST Studio workspace for {{orgName}} is ready", """
                    Hello {{name}},

                    Your FAST Studio workspace for {{orgName}} is ready, and you are its owner.

                    Sign in with:
                    Email: {{email}}
                    One-time password: {{temporaryPassword}}

                    {{loginUrl}}

                    First you will choose your own password. Then upload your videos, create a channel and invite your team from the Team page."""),
            new Def("password_reset", "Password reset link",
                    "Sent when an administrator creates a reset link, or when someone uses \"Forgot your password?\" on the sign-in page.",
                    List.of("name", "resetLink", "expiresAt"), List.of("resetLink"),
                    "Reset your FAST Studio password", """
                    Hello {{name}},

                    Use this link to choose a new password for FAST Studio:

                    {{resetLink}}

                    The link works once and expires on {{expiresAt}}. If you didn't ask for this, ignore this email: your current password keeps working."""),
            new Def("password_changed", "Password changed — security notice",
                    "Sent after someone changes or resets their password, so they notice if it wasn't them.",
                    List.of("name", "changedAt", "loginUrl"), List.of(),
                    "Your FAST Studio password was changed", """
                    Hello {{name}},

                    The password for your FAST Studio account was changed on {{changedAt}}.

                    If this was you, there is nothing to do. If it wasn't, contact your administrator straight away and ask for a reset link."""),
            new Def("alert_raised", "Monitoring alert",
                    "Sent to owners, admins and channel operators when monitoring finds a new problem, such as a live channel with nothing scheduled.",
                    List.of("name", "orgName", "severity", "title", "detail", "suggestion", "alertUrl"), List.of(),
                    "[{{severity}}] {{title}}", """
                    Hello {{name}},

                    Monitoring found a new problem in {{orgName}}:

                    {{title}}
                    {{detail}}

                    What to do: {{suggestion}}

                    {{alertUrl}}

                    You get one email per new problem. The alert closes by itself once a re-check finds it fixed."""),
            new Def("partner_shared", "Channels shared with a partner",
                    "Sent to a partner organization's admins when a customer shares channels with them. They accept in FAST Studio.",
                    List.of("name", "ownerName", "partnerName", "channels", "scopes", "acceptUrl"), List.of(),
                    "{{ownerName}} shared channels with {{partnerName}}", """
                    Hello {{name}},

                    {{ownerName}} wants to share channels with {{partnerName}} on FAST Studio.

                    Channels: {{channels}}
                    What you can use: {{scopes}}

                    {{acceptUrl}}

                    Open FAST Studio and press Accept to start. Everything is read-only, and {{ownerName}} can stop sharing at any time."""),
            new Def("test_message", "Test email",
                    "Sent from the Email page to check that delivery works.",
                    List.of("name"), List.of(),
                    "FAST Studio test email", """
                    Hello {{name}},

                    This is a test email from FAST Studio. If you can read it, email delivery works."""),
            new Def(FOOTER, "Footer (added to every email)",
                    "Small print at the bottom of every email. {{appUrl}} can be used here and in every template.",
                    List.of(), List.of(), null, """
                    Sent by FAST Studio · {{appUrl}}
                    This is an automatic message, so replies are not read."""));

    static final Map<String, Def> BY_KEY = new LinkedHashMap<>();
    static { DEFAULTS.forEach(d -> BY_KEY.put(d.key(), d)); }

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<]+");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH);

    private final EmailTemplateRepository repository;

    public EmailTemplates(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    public static String when(OffsetDateTime t) {
        return t.atZoneSameInstant(ZoneId.systemDefault()).format(WHEN);
    }

    public static Def def(String key) {
        Def d = BY_KEY.get(key);
        if (d == null) throw ApiException.notFound("Email template");
        return d;
    }

    public record View(String key, String name, String description, List<String> variables, List<String> required,
                       String subject, String body, boolean customized, OffsetDateTime updatedAt, String defaultSubject, String defaultBody) {}

    public List<View> list() {
        Map<String, Saved> o = new HashMap<>();
        repository.saved().forEach(x -> o.put(x.key(), x));
        return DEFAULTS.stream().map(d -> {
            Saved x = o.get(d.key());
            List<String> vars = new ArrayList<>(d.vars());
            vars.add("appUrl");
            return new View(d.key(), d.name(), d.description(), vars, d.required(), x == null ? d.subject() : x.subject(), x == null ? d.body() : x.body(),
                    x != null, x == null ? null : x.updatedAt(), d.subject(), d.body());
        }).toList();
    }

    /** Problems a person can fix before saving; empty when the template is fine. */
    public static List<String> problems(String key, String subject, String body) {
        Def d = def(key);
        List<String> out = new ArrayList<>();
        Set<String> allowed = new HashSet<>(d.vars());
        allowed.add("appUrl");
        String all = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        Matcher m = VAR.matcher(all);
        Set<String> used = new LinkedHashSet<>();
        while (m.find()) used.add(m.group(1));
        for (String v : used) if (!allowed.contains(v)) out.add("{{" + v + "}} is not available here. Use: " + String.join(", ", allowed.stream().sorted().map(x -> "{{" + x + "}}").toList()) + ".");
        for (String r : d.required()) if (!used.contains(r)) out.add("Keep {{" + r + "}} — without it this email is useless to the person who gets it.");
        if (d.subject() != null && (subject == null || subject.isBlank())) out.add("The subject can't be empty.");
        if (subject != null && (subject.length() > 200 || subject.contains("\n"))) out.add("Keep the subject to one line (200 characters at most).");
        if (body == null || body.isBlank()) out.add("The message can't be empty.");
        else if (body.length() > 5000) out.add("The message is too long (5,000 characters at most).");
        return out;
    }

    public void save(String key, String subject, String body, UUID by) {
        List<String> p = problems(key, subject, body);
        if (!p.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TEMPLATE_INVALID", String.join(" ", p));
        repository.save(key, def(key).subject() == null ? null : subject.strip(), body.strip(), by);
    }

    public void reset(String key) {
        def(key);
        repository.delete(key);
    }

    public record Rendered(String subject, String text, String html) {}

    /** Renders with the saved (or default) template and footer; redact hides one-time secrets for the log. */
    public Rendered render(String key, Map<String, String> vars, String appUrl, boolean redact) {
        View t = list().stream().filter(v -> v.key().equals(key)).findFirst().orElseThrow(() -> ApiException.notFound("Email template"));
        View footer = list().stream().filter(v -> v.key().equals(FOOTER)).findFirst().orElseThrow();
        return render(t.subject(), t.body(), footer.body(), vars, appUrl, redact);
    }

    public static Rendered render(String subject, String body, String footer, Map<String, String> vars, String appUrl, boolean redact) {
        Map<String, String> v = new HashMap<>(vars);
        v.put("appUrl", appUrl);
        if (redact) SECRETS.forEach(s -> { if (v.containsKey(s)) v.put(s, HIDDEN); });
        String subj = fill(subject == null ? "" : subject, v).replaceAll("\\s+", " ").strip();
        String text = fill(body, v).strip();
        String foot = fill(footer, v).strip();
        StringBuilder html = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n")) html.append(paragraph(para.strip()));
        String page = """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>
                <body style="margin:0;background:#f4f6fb;font-family:'Segoe UI',Arial,sans-serif;color:#0f172a">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f6fb;padding:24px 12px"><tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border:1px solid #e2e8f0;border-radius:12px">
                <tr><td style="padding:18px 28px;border-bottom:1px solid #eef2f7;font-weight:700;font-size:16px;color:#4f46e5">&#9654; FAST Studio</td></tr>
                <tr><td style="padding:24px 28px 10px;font-size:15px;line-height:1.6">%s</td></tr>
                </table>
                <div style="max-width:560px;color:#64748b;font-size:12px;line-height:1.5;margin:14px auto 0;text-align:left">%s</div>
                </td></tr></table></body></html>""".formatted(html, lines(foot));
        return new Rendered(subj, text + "\n\n--\n" + foot, page);
    }

    private static String fill(String s, Map<String, String> v) {
        return VAR.matcher(s).replaceAll(m -> Matcher.quoteReplacement(Objects.requireNonNullElse(v.get(m.group(1)), "")));
    }

    /** A paragraph that is just a link becomes a button (with the address underneath for clients that block buttons). */
    private static String paragraph(String p) {
        if (p.isEmpty()) return "";
        if (p.matches("https?://\\S+") || p.equals(HIDDEN)) {
            String url = HtmlUtils.htmlEscape(p);
            String label = p.contains("/reset-password") || p.equals(HIDDEN) ? "Choose a new password" : p.endsWith("/login") ? "Sign in to FAST Studio" : "Open in FAST Studio";
            return "<p style=\"margin:22px 0 6px\"><a href=\"" + url + "\" style=\"background:#4f46e5;color:#ffffff;text-decoration:none;padding:11px 20px;border-radius:8px;font-weight:600;display:inline-block\">"
                    + label + "</a></p><p style=\"margin:0 0 16px;font-size:12px;color:#64748b;word-break:break-all\">Or copy this address: " + url + "</p>";
        }
        return "<p style=\"margin:0 0 14px\">" + lines(p) + "</p>";
    }

    private static String lines(String s) {
        String esc = HtmlUtils.htmlEscape(s);
        return URL.matcher(esc).replaceAll(m -> Matcher.quoteReplacement("<a href=\"" + m.group() + "\" style=\"color:#4f46e5\">" + m.group() + "</a>"))
                .replace("\n", "<br>");
    }

    /** Example values for previews. */
    public static Map<String, String> sample(String appUrl) {
        return Map.ofEntries(Map.entry("name", "Asha Rao"), Map.entry("email", "asha@example.com"), Map.entry("orgName", "Aurora Media"),
                Map.entry("inviterName", "Ravi Menon"), Map.entry("role", "Channel operator"), Map.entry("temporaryPassword", "Xy7kPq2mNs8vRt"),
                Map.entry("loginUrl", appUrl + "/login"), Map.entry("resetLink", appUrl + "/reset-password?token=example-token"),
                Map.entry("expiresAt", when(OffsetDateTime.now().plusHours(24))), Map.entry("changedAt", when(OffsetDateTime.now())),
                Map.entry("severity", "Critical"), Map.entry("title", "Aurora Action is live but has nothing scheduled"),
                Map.entry("detail", "Viewers are seeing the standby screen."), Map.entry("suggestion", "Open the schedule, auto-fill and publish."),
                Map.entry("alertUrl", appUrl + "/aurora/monitoring"), Map.entry("ownerName", "Aurora Media"), Map.entry("partnerName", "Partner TV"),
                Map.entry("channels", "Aurora Action, Classic Cinema"), Map.entry("scopes", "Channel details, Programme guide (EPG and XMLTV)"),
                Map.entry("acceptUrl", appUrl + "/partner-tv"));
    }
}
