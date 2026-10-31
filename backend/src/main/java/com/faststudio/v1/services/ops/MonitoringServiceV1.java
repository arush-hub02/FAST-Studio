package com.faststudio.v1.services.ops;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.ops.OpsDto.*;
import com.faststudio.v1.repositories.ops.MonitoringRepository;
import com.faststudio.v1.services.playout.Scte35;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * Operations views of a project: home dashboard with a getting-started checklist, alerts (acknowledge / resolve /
 * scan now), audit log, ad-avail verification results, prototype analytics, and the SCTE-35 learning tool.
 */
@Service
public class MonitoringServiceV1 {
    private final Data data;
    private final MonitoringRepository repository;
    private final AlertScanner scanner;
    private final AvailVerifier avails;
    private final Audit audit;

    public MonitoringServiceV1(Data data, MonitoringRepository repository, AlertScanner scanner, AvailVerifier avails, Audit audit) {
        this.data = data;
        this.repository = repository;
        this.scanner = scanner;
        this.avails = avails;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ dashboard

    public Dashboard dashboard(Tenant t) {
        UUID o = t.orgId();
        Map<String, Long> c = new LinkedHashMap<>();
        c.put("channels", repository.channels(o));
        c.put("liveChannels", repository.liveChannels(o));
        c.put("videos", repository.videos(o));
        c.put("processing", repository.processing(o));
        c.put("activeCampaigns", repository.activeCampaigns(o));
        c.put("openAlerts", repository.openAlerts(o));
        c.put("impressions", repository.impressions(o));
        c.put("viewerSessions", repository.viewerSessions(o));
        List<Step> steps = List.of(
                new Step("video", "Add a video", "Upload a file or generate a sample programme.", repository.readyVideos(o) > 0, "/library"),
                new Step("rights", "Set where it may play", "A licence says which countries and dates are allowed.", repository.rightsSet(o) > 0, "/rights"),
                new Step("channel", "Create a channel", "Give it a name, a look and its countries.", c.get("channels") > 0, "/channels"),
                new Step("schedule", "Build and publish a schedule", "Auto-fill the day, fix any warnings, publish.", repository.publishedSchedules(o) > 0, "/schedule"),
                new Step("live", "Go live", "Start playout for the channel.", c.get("liveChannels") > 0, "/channels"),
                new Step("watch", "Watch it with ads", "Open the player and see SSAI fill an ad break.", c.get("impressions") > 0, "/watch"),
                new Step("distribute", "Send it to a destination", "Check the feed against a platform's rules.", repository.successfulDeliveries(o) > 0, "/distribution"));
        return new Dashboard(c, steps, repository.audit(o, 8), alerts(t, "open").stream().limit(5).toList());
    }

    // ------------------------------------------------------------------ alerts

    /** {@code filter} "all" includes resolved alerts; anything else shows open ones. */
    public List<Alert> alerts(Tenant t, String filter) {
        return repository.alerts(t.orgId(), !filter.equals("all"));
    }

    public Map<String, String> acknowledge(Tenant t, UUID id) {
        t.require(Tenant.Perm.ALERTS_EDIT);
        if (!repository.acknowledge(t.orgId(), id)) throw ApiException.conflict("NOT_OPEN", "Only open alerts can be acknowledged.");
        audit.log(t, "alert.acknowledge", "alert", id, "");
        return Map.of("status", "ACKNOWLEDGED");
    }

    /** Resolution needs a note. The scanner re-opens it on the next pass if the underlying problem is still there. */
    public Map<String, String> resolve(Tenant t, UUID id, ResolveRequest r) {
        t.require(Tenant.Perm.ALERTS_EDIT);
        if (!repository.resolve(t.orgId(), id, r.resolution())) throw ApiException.notFound("Open alert");
        audit.log(t, "alert.resolve", "alert", id, r.resolution());
        return Map.of("status", "RESOLVED");
    }

    public List<Alert> scanNow(Tenant t) {
        scanner.scanOrg(t.orgId());
        return alerts(t, "open");
    }

    /** Latest avail check per marker format; empty until a break has aired since the server started. */
    public List<AvailVerifier.Result> avails(Tenant t, UUID channel) {
        data.channel(t.orgId(), channel);   // 404 unless it is this project's channel
        return avails.results(channel);
    }

    public List<AuditRow> audit(Tenant t, int limit) { return repository.audit(t.orgId(), Math.min(limit, 500)); }

    // ------------------------------------------------------------------ analytics

    /** Observed by this prototype's playout; estimated revenue uses fictional campaign CPMs and is not money received. */
    public Analytics analytics(Tenant t) {
        UUID o = t.orgId();
        List<ChannelStats> channels = repository.channelStats(o).stream().map(r -> new ChannelStats(r.channelId(), r.channel(), r.sessions(),
                r.impressions(), r.completes(), r.adSeconds(), r.slateSeconds(), ratio(r.adSeconds(), r.adSeconds() + r.slateSeconds()),
                ratio(r.completes(), r.impressions()), r.revenue().setScale(2, RoundingMode.HALF_EVEN))).toList();
        List<CampaignStats> campaigns = repository.campaignStats(o).stream()
                .map(c -> new CampaignStats(c.campaign(), c.advertiser(), c.impressions(), c.completes(), c.cpm(), c.estimatedRevenue().setScale(2, RoundingMode.HALF_EVEN)))
                .toList();
        return new Analytics("PROTOTYPE_OBSERVED",
                "Counted from ad segments actually delivered by this prototype's playout (SSAI) or player beacons (SGAI). "
                        + "Estimated revenue = impressions × campaign CPM ÷ 1000, using the CPMs typed into fictional campaigns. Not market data, not money received.",
                channels, campaigns, repository.lastWeek(o));
    }

    /** a/b with 4 decimals, or null when b is 0. */
    private static BigDecimal ratio(long a, long b) {
        return b == 0 ? null : BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 4, RoundingMode.HALF_EVEN);
    }

    // ------------------------------------------------------------------ SCTE-35 learning tool

    public Map<String, Object> decodeScte35(String value) {
        try {
            return Scte35.decode(Scte35.parseInput(value));
        } catch (RuntimeException e) {
            throw ApiException.bad("Couldn't decode that as SCTE-35 (paste hex starting with 0xFC… or base64 starting with /DA…): " + e.getMessage());
        }
    }

    /** A 90-second provider placement opportunity signal for "now", in hex, base64 and decoded form. */
    public Map<String, Object> scte35Example() {
        long now = Instant.now().toEpochMilli();
        byte[] b = Scte35.timeSignal(Scte35.ticks(now), 1234, Scte35.PROVIDER_PO_START, 90L * 90000);
        return Map.of("hex", Scte35.hex(b), "base64", Base64.getEncoder().encodeToString(b), "decoded", Scte35.decode(b));
    }
}
