package com.faststudio.v1.services.ops;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.tenant.TenantScope;
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
    private final MailServiceV1 mail;
    private final AvailVerifier avails;

    public AlertScanner(Data data, MailServiceV1 mail, AvailVerifier avails) {
        this.data = data;
        this.mail = mail;
        this.avails = avails;
    }

    record Recipient(String email, String name) {}

    record Org(String name, String slug) {}

    /** One email per newly raised alert to the people who can act on it. */
    private void notify(UUID org, A a) {
        Org o = data.db.sql("select name, slug from organization where id = :o").param("o", org).query(Org.class).single();
        for (Recipient r : data.db.sql("""
                select u.email, u.name from membership m join app_user u on u.id = m.user_id
                where m.org_id = :o and m.role in ('OWNER', 'ADMIN', 'CHANNEL_OPERATOR')""").param("o", org).query(Recipient.class).list())
            mail.send(org, "alert_raised", r.email(), Map.of("name", r.name(), "orgName", o.name(),
                    "severity", a.severity().charAt(0) + a.severity().substring(1).toLowerCase(Locale.ROOT), "title", a.title(),
                    "detail", a.detail(), "suggestion", a.suggestion(), "alertUrl", mail.appUrl() + "/" + o.slug() + "/monitoring"));
    }

    record A(String key, String severity, String title, String detail, String suggestion, String link) {}

    record Expiring(UUID assetId, String title, OffsetDateTime validUntil) {}

    @Scheduled(initialDelay = 20_000, fixedDelay = 60_000)
    public void scanAll() {
        // each organisation is checked inside its own tenant scope (row-level security applies)
        for (UUID org : data.db.sql("select id from organization where status = 'ACTIVE' and kind = 'CUSTOMER'").query(UUID.class).list())
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
        data.db.sql("select id, title from asset where org_id = :o and status = 'FAILED'").param("o", org)
                .query((rs, i) -> new A("failed:" + rs.getString("id"), "WARNING", "Video failed to process: " + rs.getString("title"),
                        "The file could not be converted for playout.", "Open the video and retry, or upload a different file.", "/library?asset=" + rs.getString("id")))
                .list().forEach(found::add);
        data.db.sql("""
                select distinct a.id as asset_id, a.title, r.valid_until from schedule_entry e
                join channel c on c.org_id = e.org_id and c.published_version_id = e.version_id
                join asset a on a.org_id = e.org_id and a.id = e.asset_id
                join rights_policy r on r.org_id = a.org_id and r.asset_id = a.id
                where e.org_id = :o and e.end_at > now() and r.valid_until is not null and r.valid_until < now() + interval '7 days'""")
                .param("o", org).query(Expiring.class).list()
                .forEach(x -> found.add(new A("expiring:" + x.assetId(), "WARNING", "Licence ending soon: " + x.title(),
                        "Rights end " + x.validUntil().toLocalDate() + " and the video is still in a published schedule.",
                        "Renew the licence or remove it from upcoming schedules.", "/rights?asset=" + x.assetId())));

        Set<String> keys = new HashSet<>();
        for (A a : found) {
            keys.add(a.key());
            boolean raised = data.db.sql("""
                    insert into alert(org_id, dedup_key, severity, title, detail, suggestion, link) values (:o,:k,:s,:t,:d,:sg,:l)
                    on conflict (org_id, dedup_key) where status <> 'RESOLVED' do update set detail = excluded.detail, updated_at = now()
                    returning (xmax = 0)""")
                    .param("o", org).param("k", a.key()).param("s", a.severity()).param("t", a.title()).param("d", a.detail())
                    .param("sg", a.suggestion()).param("l", a.link()).query(Boolean.class).single();
            if (raised) notify(org, a);   // xmax = 0: this row was inserted now, not updated
        }
        // auto-resolve scanner-owned alerts whose condition is gone
        for (var row : data.db.sql("select id, dedup_key from alert where org_id = :o and status <> 'RESOLVED'").param("o", org)
                .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("dedup_key"))).list()) {
            String k = row.getValue();
            boolean scannerOwned = k.startsWith("off-air:") || k.startsWith("coverage:") || k.startsWith("failed:") || k.startsWith("expiring:") || k.startsWith("avails:");
            if (scannerOwned && !keys.contains(k))
                data.db.sql("update alert set status = 'RESOLVED', resolution = 'Cleared automatically: re-check found the problem fixed.', updated_at = now() where id = :id")
                        .param("id", row.getKey()).update();
        }
    }
}
