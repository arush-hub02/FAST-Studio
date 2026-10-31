package com.faststudio.v1.services.ops;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.repositories.ops.MonitoringRepository;
import com.faststudio.v1.repositories.ops.MonitoringRepository.AlertOrg;
import com.faststudio.v1.services.mail.MailServiceV1;
import com.faststudio.v1.services.programming.Planner;
import com.faststudio.v1.services.rights.Rights;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Raises alerts from real prototype state and auto-resolves them only when a re-check proves the condition cleared.
 * Acknowledging an alert never fixes it.
 */
@Component
public class AlertScanner {
    private final Data data;
    private final MonitoringRepository repository;
    private final MailServiceV1 mail;
    private final AvailVerifier avails;

    public AlertScanner(Data data, MonitoringRepository repository, MailServiceV1 mail, AvailVerifier avails) {
        this.data = data;
        this.repository = repository;
        this.mail = mail;
        this.avails = avails;
    }

    /** One email per newly raised alert to the people who can act on it. */
    private void notify(UUID org, A a) {
        AlertOrg o = repository.alertOrg(org);
        for (var r : repository.alertRecipients(org))
            mail.send(org, "alert_raised", r.email(), Map.of("name", r.name(), "orgName", o.name(),
                    "severity", a.severity().charAt(0) + a.severity().substring(1).toLowerCase(Locale.ROOT), "title", a.title(),
                    "detail", a.detail(), "suggestion", a.suggestion(), "alertUrl", mail.appUrl() + "/" + o.slug() + "/monitoring"));
    }

    record A(String key, String severity, String title, String detail, String suggestion, String link) {}

    @Scheduled(initialDelay = 20_000, fixedDelay = 60_000)
    public void scanAll() {
        // each organisation is checked inside its own tenant scope (row-level security applies)
        for (UUID org : repository.activeCustomerProjects())
            TenantScope.as(org, () -> { scanOrg(org); return null; });
    }

    public void scanOrg(UUID org) {
        Instant now = Instant.now();
        List<A> found = new ArrayList<>();
        for (Data.Channel c : data.channels(org)) {
            if (!c.status().equals("LIVE")) continue;
            Instant end = data.coverageEnd(org, c.publishedVersionId());
            if (end == null || !end.isAfter(now))
                found.add(new A("off-air:" + c.id(), "CRITICAL", c.name() + " is live but has nothing scheduled",
                        "Viewers are seeing the standby screen.", "Open the schedule, auto-fill and publish.", "/schedule?channel=" + c.id()));
            else if (end.isBefore(now.plusSeconds(6 * 3600)))
                found.add(new A("coverage:" + c.id(), "WARNING", c.name() + ": schedule runs out soon",
                        "The published schedule ends " + Planner.fmt(end, c.timezone()) + ".", "Auto-fill more hours and publish.", "/schedule?channel=" + c.id()));
            for (AvailVerifier.Result r : avails.results(c.id()))
                if (!r.passed())
                    found.add(new A("avails:" + c.id() + ":" + r.markers(), "CRITICAL", c.name() + ": ad breaks earn nothing on " + r.destination(),
                            "The break at " + Planner.fmt(r.breakStart(), c.timezone()) + " went out with broken " + r.markers() + " markers: "
                                    + String.join("; ", r.problems()) + ". The stream plays on, but players and ad servers can't fill these breaks.",
                            "Check the edge has the published schedule and the destination's marker format. This clears when a later break checks clean.",
                            "/schedule?channel=" + c.id()));
        }
        repository.failedAssets(org).forEach(x -> found.add(new A("failed:" + x.id(), "WARNING", "Video failed to process: " + x.title(),
                "The file could not be converted for playout.", "Open the video and retry, or upload a different file.", "/library?asset=" + x.id())));
        repository.expiringLicences(org)
                .forEach(x -> found.add(new A("expiring:" + x.assetId(), "WARNING", "Licence ending soon: " + x.title(),
                        "Rights end " + x.validUntil().toLocalDate() + " and the video is still in a published schedule.",
                        "Renew the licence or remove it from upcoming schedules.", "/rights?asset=" + x.assetId())));

        Set<String> keys = new HashSet<>();
        for (A a : found) {
            keys.add(a.key());
            boolean raised = repository.raiseAlert(org, a.key(), a.severity(), a.title(), a.detail(), a.suggestion(), a.link());
            if (raised) notify(org, a);   // xmax = 0: this row was inserted now, not updated
        }
        // auto-resolve scanner-owned alerts whose condition is gone
        for (var row : repository.unresolvedAlerts(org)) {
            String k = row.dedupKey();
            boolean scannerOwned = k.startsWith("off-air:") || k.startsWith("coverage:") || k.startsWith("failed:") || k.startsWith("expiring:") || k.startsWith("avails:");
            if (scannerOwned && !keys.contains(k))
                repository.autoResolve(row.id());
        }
    }
}
