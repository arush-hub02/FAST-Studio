package com.faststudio.v1_0.controllers.studio.monitoring;

import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.services.ops.AlertScanner;
import com.faststudio.v1_0.services.ops.AvailVerifier;
import com.faststudio.v1_0.services.playout.Scte35;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class OpsControllerV1_0 {
    private final Data data;
    private final Audit audit;
    private final AlertScanner scanner;
    private final AvailVerifier avails;

    public OpsControllerV1_0(Data data, Audit audit, AlertScanner scanner, AvailVerifier avails) {
        this.data = data;
        this.audit = audit;
        this.scanner = scanner;
        this.avails = avails;
    }

    // ------------------------------------------------------------------ home dashboard

    public record Step(String key, String title, String description, boolean done, String link) {}

    public record Dashboard(Map<String, Long> counts, List<Step> checklist, List<AuditRow> activity, List<Alert> alerts) {}

    @GetMapping("/orgs/{org}/dashboard")
    Dashboard dashboard(Tenant t) {
        UUID o = t.orgId();
        Map<String, Long> c = new LinkedHashMap<>();
        c.put("channels", count("select count(*) from channel where org_id = :o and status <> 'ARCHIVED'", o));
        c.put("liveChannels", count("select count(*) from channel where org_id = :o and status = 'LIVE'", o));
        c.put("videos", count("select count(*) from asset where org_id = :o and kind <> 'AD' and status <> 'ARCHIVED'", o));
        c.put("processing", count("select count(*) from asset where org_id = :o and status = 'PROCESSING'", o));
        c.put("activeCampaigns", count("select count(*) from campaign where org_id = :o and status = 'ACTIVE'", o));
        c.put("openAlerts", count("select count(*) from alert where org_id = :o and status <> 'RESOLVED'", o));
        c.put("impressions", count("select count(*) from ad_event where org_id = :o and event = 'IMPRESSION'", o));
        c.put("viewerSessions", count("select count(*) from playback_session where org_id = :o", o));
        List<Step> steps = List.of(
                new Step("video", "Add a video", "Upload a file or generate a sample programme.", count("select count(*) from asset where org_id = :o and kind <> 'AD' and status = 'READY'", o) > 0, "/library"),
                new Step("rights", "Set where it may play", "A licence says which countries and dates are allowed.", count("select count(*) from rights_policy where org_id = :o and allowed_countries <> ''", o) > 0, "/rights"),
                new Step("channel", "Create a channel", "Give it a name, a look and its countries.", c.get("channels") > 0, "/channels"),
                new Step("schedule", "Build and publish a schedule", "Auto-fill the day, fix any warnings, publish.", count("select count(*) from schedule_version where org_id = :o and status = 'PUBLISHED'", o) > 0, "/schedule"),
                new Step("live", "Go live", "Start playout for the channel.", c.get("liveChannels") > 0, "/channels"),
                new Step("watch", "Watch it with ads", "Open the player and see SSAI fill an ad break.", c.get("impressions") > 0, "/watch"),
                new Step("distribute", "Send it to a destination", "Check the feed against a platform's rules.", count("select count(*) from publication where org_id = :o and status = 'SUCCEEDED'", o) > 0, "/distribution"));
        return new Dashboard(c, steps, auditRows(o, 8), alerts(t, "open").stream().limit(5).toList());
    }

    long count(String sql, UUID org) {
        return data.db.sql(sql).param("o", org).query(Long.class).single();
    }

    // ------------------------------------------------------------------ alerts

    public record Alert(UUID id, String severity, String title, String detail, String suggestion, String link, String status,
                        String resolution, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    @GetMapping("/orgs/{org}/alerts")
    List<Alert> alerts(Tenant t, @RequestParam(defaultValue = "open") String filter) {
        String where = filter.equals("all") ? "" : " and status <> 'RESOLVED'";
        return data.db.sql("select id, severity, title, detail, suggestion, link, status, resolution, created_at, updated_at from alert where org_id = :o" + where
                        + " order by case severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end, created_at desc limit 100")
                .param("o", t.orgId()).query(Alert.class).list();
    }

    @PostMapping("/orgs/{org}/alerts/{id}/acknowledge")
    Map<String, String> ack(Tenant t, @PathVariable UUID id) {
        t.require(Tenant.Perm.ALERTS_EDIT);
        int n = data.db.sql("update alert set status = 'ACKNOWLEDGED', updated_at = now() where org_id = :o and id = :id and status = 'OPEN'")
                .param("o", t.orgId()).param("id", id).update();
        if (n == 0) throw ApiException.conflict("NOT_OPEN", "Only open alerts can be acknowledged.");
        audit.log(t, "alert.acknowledge", "alert", id, "");
        return Map.of("status", "ACKNOWLEDGED");
    }

    public record ResolveRequest(@NotBlank @Size(max = 500) String resolution) {}

    /** Resolution needs a note. The scanner re-opens it on the next pass if the underlying problem is still there. */
    @PostMapping("/orgs/{org}/alerts/{id}/resolve")
    Map<String, String> resolve(Tenant t, @PathVariable UUID id, @Valid @RequestBody ResolveRequest r) {
        t.require(Tenant.Perm.ALERTS_EDIT);
        int n = data.db.sql("update alert set status = 'RESOLVED', resolution = :r, updated_at = now() where org_id = :o and id = :id and status <> 'RESOLVED'")
                .param("r", r.resolution()).param("o", t.orgId()).param("id", id).update();
        if (n == 0) throw ApiException.notFound("Open alert");
        audit.log(t, "alert.resolve", "alert", id, r.resolution());
        return Map.of("status", "RESOLVED");
    }

    @PostMapping("/orgs/{org}/alerts/scan")
    List<Alert> scanNow(Tenant t) {
        scanner.scanOrg(t.orgId());
        return alerts(t, "open");
    }

    /** Latest avail check per marker format (AvailVerifier); empty until a break has aired since the server started. */
    @GetMapping("/orgs/{org}/channels/{id}/avails")
    List<AvailVerifier.Result> avails(Tenant t, @PathVariable UUID id) {
        data.channel(t.orgId(), id);   // 404 unless it is this organisation's channel
        return avails.results(id);
    }

    // ------------------------------------------------------------------ audit

    public record AuditRow(long id, String actor, String action, String resourceType, String resourceId, String detail, OffsetDateTime createdAt) {}

    @GetMapping("/orgs/{org}/audit")
    List<AuditRow> audit(Tenant t, @RequestParam(defaultValue = "100") int limit) {
        return auditRows(t.orgId(), Math.min(limit, 500));
    }

    List<AuditRow> auditRows(UUID org, int limit) {
        return data.db.sql("""
                select e.id, coalesce(u.name || coalesce(' (' || v.name || ')', ''), v.name || ' (API)', 'System') as actor,
                  e.action, e.resource_type, e.resource_id, e.detail, e.created_at
                from audit_event e left join app_user u on u.id = e.actor_id left join organization v on v.id = e.via_org_id
                where e.org_id = :o order by e.id desc limit :l""")
                .param("o", org).param("l", limit).query(AuditRow.class).list();
    }

    // ------------------------------------------------------------------ analytics

    public record ChannelStats(UUID channelId, String channel, long sessions, long impressions, long completes, long adSeconds,
                               long slateSeconds, BigDecimal fillRate, BigDecimal completionRate, BigDecimal estimatedRevenue) {}

    public record CampaignStats(String campaign, String advertiser, long impressions, long completes, BigDecimal cpm, BigDecimal estimatedRevenue) {}

    public record DayStat(LocalDate day, long impressions, long sessions) {}

    public record Analytics(String provenance, String formula, List<ChannelStats> channels, List<CampaignStats> campaigns, List<DayStat> days) {}

    record ChRow(UUID channelId, String channel, long sessions, long impressions, long completes, long adSeconds, long slateSeconds, BigDecimal revenue) {}

    @GetMapping("/orgs/{org}/analytics")
    Analytics analytics(Tenant t) {
        UUID o = t.orgId();
        var rows = data.db.sql("""
                select c.id as channel_id, c.name as channel,
                  (select count(*) from playback_session s where s.org_id = c.org_id and s.channel_id = c.id) as sessions,
                  count(e.id) filter (where e.event = 'IMPRESSION') as impressions,
                  count(e.id) filter (where e.event = 'COMPLETE' and exists (select 1 from ad_event i where i.session_id = e.session_id and i.break_id = e.break_id and i.campaign_id = e.campaign_id and i.event = 'IMPRESSION')) as completes,
                  coalesce(sum(e.seconds) filter (where e.event = 'IMPRESSION'), 0) as ad_seconds,
                  coalesce(sum(e.seconds) filter (where e.event = 'SLATE'), 0) as slate_seconds,
                  coalesce(sum(cp.cpm) filter (where e.event = 'IMPRESSION'), 0) / 1000 as revenue
                from channel c
                left join ad_event e on e.org_id = c.org_id and e.channel_id = c.id
                left join campaign cp on cp.org_id = e.org_id and cp.id = e.campaign_id
                where c.org_id = :o and c.status <> 'ARCHIVED' group by c.id, c.name order by c.name""").param("o", o).query(ChRow.class).list();
        List<ChannelStats> channels = rows.stream().map(r -> new ChannelStats(r.channelId(), r.channel(), r.sessions(), r.impressions(), r.completes(),
                r.adSeconds(), r.slateSeconds(), ratio(r.adSeconds(), r.adSeconds() + r.slateSeconds()), ratio(r.completes(), r.impressions()),
                r.revenue().setScale(2, RoundingMode.HALF_EVEN))).toList();
        var campaigns = data.db.sql("""
                select c.name as campaign, c.advertiser,
                  count(e.id) filter (where e.event = 'IMPRESSION') as impressions,
                  count(e.id) filter (where e.event = 'COMPLETE' and exists (select 1 from ad_event i where i.session_id = e.session_id and i.break_id = e.break_id and i.campaign_id = e.campaign_id and i.event = 'IMPRESSION')) as completes, c.cpm,
                  (count(e.id) filter (where e.event = 'IMPRESSION')) * c.cpm / 1000 as estimated_revenue
                from campaign c left join ad_event e on e.org_id = c.org_id and e.campaign_id = c.id
                where c.org_id = :o group by c.id, c.name, c.advertiser, c.cpm order by impressions desc, c.name""")
                .param("o", o).query(CampaignStats.class).list().stream()
                .map(c -> new CampaignStats(c.campaign(), c.advertiser(), c.impressions(), c.completes(), c.cpm(), c.estimatedRevenue().setScale(2, RoundingMode.HALF_EVEN))).toList();
        var days = data.db.sql("""
                select d::date as day,
                  (select count(*) from ad_event e where e.org_id = :o and e.event = 'IMPRESSION' and e.occurred_at::date = d::date) as impressions,
                  (select count(*) from playback_session s where s.org_id = :o and s.created_at::date = d::date) as sessions
                from generate_series(current_date - 6, current_date, interval '1 day') d order by d""").param("o", o).query(DayStat.class).list();
        return new Analytics("PROTOTYPE_OBSERVED",
                "Counted from ad segments actually delivered by this prototype's playout (SSAI) or player beacons (SGAI). "
                        + "Estimated revenue = impressions × campaign CPM ÷ 1000, using the CPMs typed into fictional campaigns. Not market data, not money received.",
                channels, campaigns, days);
    }

    static BigDecimal ratio(long a, long b) {
        return b == 0 ? null : BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 4, RoundingMode.HALF_EVEN);
    }

    // ------------------------------------------------------------------ learning tools

    public record DecodeRequest(@NotBlank String value) {}

    @PostMapping("/tools/scte35/decode")
    Map<String, Object> decode(@Valid @RequestBody DecodeRequest r) {
        try {
            return Scte35.decode(Scte35.parseInput(r.value()));
        } catch (RuntimeException e) {
            throw ApiException.bad("Couldn't decode that as SCTE-35 (paste hex starting with 0xFC… or base64 starting with /DA…): " + e.getMessage());
        }
    }

    @GetMapping("/tools/scte35/example")
    Map<String, Object> example() {
        long now = Instant.now().toEpochMilli();
        byte[] b = Scte35.timeSignal(Scte35.ticks(now), 1234, Scte35.PROVIDER_PO_START, 90L * 90000);
        return Map.of("hex", Scte35.hex(b), "base64", Base64.getEncoder().encodeToString(b), "decoded", Scte35.decode(b));
    }
}
