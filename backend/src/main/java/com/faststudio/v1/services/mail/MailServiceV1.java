package com.faststudio.v1.services.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.faststudio.v1.repositories.mail.EmailMessageRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Outbox for every email. send() stores the message (status QUEUED) in the caller's transaction; after commit a
 * worker hands it to mailer/send.mjs (nodemailer). With FASTSTUDIO_SMTP_URL set it is delivered (SENT); without,
 * nodemailer writes a .eml file to <data-dir>/mail-outbox (SAVED). The log keeps a copy with one-time secrets
 * hidden; the real copy is deleted once delivered. Queued emails survive a restart.
 */
@Service
public class MailServiceV1 {
    private static final Logger log = LoggerFactory.getLogger(MailServiceV1.class);

    private final EmailMessageRepository messages;
    private final EmailTemplates templates;
    private final JsonMapper json;
    private final String smtpUrl, from, appUrl, node;
    private final Path outbox;
    // ponytail: one node process per email, one at a time; fine for account and alert mail, batch it for newsletters.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public MailServiceV1(EmailMessageRepository messages, EmailTemplates templates, JsonMapper json,
                       @Value("${faststudio.mail.smtp-url:}") String smtpUrl, @Value("${faststudio.mail.from}") String from,
                       @Value("${faststudio.app-url}") String appUrl, @Value("${faststudio.mail.node:node}") String node,
                       @Value("${faststudio.data-dir}") String dataDir) {
        this.messages = messages;
        this.templates = templates;
        this.json = json;
        this.smtpUrl = smtpUrl.strip();
        this.from = from;
        this.appUrl = appUrl.replaceAll("/+$", "");
        this.node = node;
        this.outbox = Path.of(dataDir, "mail-outbox").toAbsolutePath().normalize();
    }

    public String appUrl() { return appUrl; }

    /** True when emails really leave the building (a mail server is configured). */
    public boolean delivers() { return !smtpUrl.isEmpty(); }

    static Path script() {
        for (String c : List.of("../mailer/send.mjs", "mailer/send.mjs")) {
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.exists(p) && Files.isDirectory(p.getParent().resolve("node_modules/nodemailer"))) return p;
        }
        return null;
    }

    public record Status(String mode, String from, String server, boolean mailerInstalled, String outbox, String appUrl) {}

    public Status status() {
        String server = null;
        if (delivers()) {
            try {
                URI u = URI.create(smtpUrl);                                  // never show the credentials
                server = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            } catch (Exception e) { server = "(unreadable FASTSTUDIO_SMTP_URL)"; }
        }
        return new Status(delivers() ? "SMTP" : "OUTBOX", from, server, script() != null, outbox.toString(), appUrl);
    }

    /** Queues an email. Inside a transaction it is only handed over after commit, so a rollback sends nothing. */
    public UUID send(UUID orgId, String template, String to, Map<String, String> vars) {
        var real = templates.render(template, vars, appUrl, false);
        var shown = templates.render(template, vars, appUrl, true);
        UUID id = messages.insertQueued(orgId, template, to, shown.subject(), shown.text(), shown.html(), real.subject(), real.text(), real.html());
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { worker.submit(() -> deliver(id)); }
            });
        else worker.submit(() -> deliver(id));
        return id;
    }

    public boolean retry(UUID id) {
        boolean queued = messages.requeueFailed(id);
        if (queued) worker.submit(() -> deliver(id));
        return queued;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeQueued() {
        messages.queuedIds().forEach(id -> worker.submit(() -> deliver(id)));
    }

    void deliver(UUID id) {
        EmailMessageRepository.Pending p = messages.pending(id).orElse(null);
        if (p == null) return;
        String status, detail;
        try {
            Path script = script();
            if (script == null) throw new IllegalStateException("The mailer is not installed: run 'npm install' in the mailer folder.");
            ProcessBuilder pb = new ProcessBuilder(node, script.toString()).redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().remove("SMTP_URL");
            if (delivers()) pb.environment().put("SMTP_URL", smtpUrl);
            Process proc = pb.start();
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("from", from);
            msg.put("to", p.toEmail());
            msg.put("subject", p.pendingSubject());
            msg.put("text", p.pendingText());
            msg.put("html", p.pendingHtml());
            msg.put("outboxFile", outbox.resolve(id + ".eml").toString());
            try (OutputStream in = proc.getOutputStream()) { in.write(json.writeValueAsBytes(msg)); }
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!proc.waitFor(90, TimeUnit.SECONDS)) { proc.destroyForcibly(); throw new IllegalStateException("The mail server did not answer in time."); }
            JsonNode r = json.readTree(out.isEmpty() ? "{}" : out.lines().reduce((a, b) -> b).orElse("{}"));
            if (!r.path("ok").asBoolean(false)) throw new IllegalStateException(r.path("error").asString("The mailer stopped without saying why."));
            status = r.path("delivered").asBoolean(false) ? "SENT" : "SAVED";
            detail = r.path("detail").asString("");
        } catch (Exception e) {
            status = "FAILED";
            detail = Objects.requireNonNullElse(e.getMessage(), e.toString());
            log.warn("Email {} to {} failed: {}", id, p.toEmail(), detail);
        }
        boolean done = !status.equals("FAILED");
        messages.recordAttempt(id, status, detail.length() > 500 ? detail.substring(0, 500) : detail, done);
    }
}
