package com.faststudio.distribution;

import com.faststudio.core.*;
import com.faststudio.core.Data.Channel;
import com.faststudio.core.Data.Entry;
import com.faststudio.schedule.Planner;
import com.faststudio.schedule.ScheduleController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Destinations carry a delivery profile (ad mode, marker format, ad-load limit). Publishing runs a real conformance check
 * against the published schedule; partner hand-off itself is simulated and labelled as such.
 */
@RestController
@RequestMapping("/api/orgs/{org}")
public class DistributionController {
    private final Data data;
    private final Audit audit;

    public DistributionController(Data data, Audit audit) {
        this.data = data;
        this.audit = audit;
    }

    public record Destination(UUID id, String name, String kind, String adMode, String markerFormat, int maxAdSecondsPerHour,
                              String notes, OffsetDateTime createdAt) {}

    public record Publication(UUID id, UUID channelId, String channel, UUID destinationId, String destination, Integer versionNumber,
                              String status, String message, boolean simulated, OffsetDateTime createdAt) {}

    @GetMapping("/destinations")
    Map<String, Object> list(Tenant t) {
        var dests = data.db.sql("select id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour, notes, created_at from destination where org_id = :o order by kind, name")
                .param("o", t.orgId()).query(Destination.class).list();
        var pubs = data.db.sql("""
                select p.id, p.channel_id, c.name as channel, p.destination_id, d.name as destination, p.version_number, p.status, p.message, p.simulated, p.created_at
                from publication p join channel c on c.id = p.channel_id join destination d on d.id = p.destination_id
                where p.org_id = :o order by p.created_at desc limit 50""").param("o", t.orgId()).query(Publication.class).list();
        return Map.of("destinations", dests, "publications", pubs);
    }

    public record DestinationRequest(@NotBlank @Size(max = 80) String name, @Pattern(regexp = "OWNED_WEB|PARTNER_TV") String kind,
                                     @Pattern(regexp = "SSAI|SGAI|PASSTHROUGH") String adMode, @Pattern(regexp = "DATERANGE|CUE_OUT") String markerFormat,
                                     @Min(0) @Max(1200) int maxAdSecondsPerHour, String notes) {}

    @PostMapping("/destinations")
    Map<String, UUID> create(Tenant t, @Valid @RequestBody DestinationRequest r) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        UUID id = data.db.sql("insert into destination(org_id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour, notes) values (:o,:n,:k,:m,:f,:max,:notes) returning id")
                .param("o", t.orgId()).param("n", r.name().trim()).param("k", r.kind()).param("m", r.adMode()).param("f", r.markerFormat())
                .param("max", r.maxAdSecondsPerHour()).param("notes", Objects.toString(r.notes(), "")).query(UUID.class).single();
        audit.log(t, "destination.create", "destination", id, r.name());
        return Map.of("id", id);
    }

    public record Check(String label, boolean passed, String detail) {}

    public record PublishResult(Publication publication, List<Check> checks) {}

    @PostMapping("/channels/{channelId}/distribute/{destinationId}")
    PublishResult publish(Tenant t, @PathVariable UUID channelId, @PathVariable UUID destinationId) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        Channel ch = data.channel(t.orgId(), channelId);
        Destination d = data.db.sql("select id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour, notes, created_at from destination where org_id = :o and id = :id")
                .param("o", t.orgId()).param("id", destinationId).query(Destination.class).optional().orElseThrow(() -> ApiException.notFound("Destination"));
        Instant now = Instant.now();
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Channel is live", ch.status().equals("LIVE"), ch.status().equals("LIVE") ? "On air" : "Channel status is " + ch.status().toLowerCase() + "; press 'Go live' first"));
        List<Entry> next24 = ch.publishedVersionId() == null ? List.of() : data.entries(t.orgId(), ch.publishedVersionId(), now, now.plusSeconds(24 * 3600));
        Instant end = data.coverageEnd(t.orgId(), ch.publishedVersionId());
        boolean covered = end != null && end.isAfter(now.plusSeconds(6 * 3600));
        checks.add(new Check("At least 6 hours of published schedule", covered, end == null ? "Nothing published" : "Scheduled until " + Planner.fmt(end, ch.timezone())));
        int programmes = ScheduleController.guide(data, t.orgId(), ch, now, now.plusSeconds(24 * 3600)).size();
        checks.add(new Check("EPG feed has programmes", programmes > 0, programmes + " programmes in the next 24 h (XMLTV)"));
        Map<Long, Long> perHour = new TreeMap<>();
        next24.stream().filter(Entry::isBreak).forEach(e -> perHour.merge(e.start().toEpochMilli() / 3_600_000L, e.durationMs() / 1000, Long::sum));
        long worst = perHour.values().stream().mapToLong(Long::longValue).max().orElse(0);
        checks.add(new Check("Ad load within destination limit", worst <= d.maxAdSecondsPerHour(),
                "Busiest hour has " + worst + " s of breaks; " + d.name() + " allows " + d.maxAdSecondsPerHour() + " s"));
        boolean hasBreaks = next24.stream().anyMatch(Entry::isBreak);
        checks.add(new Check("Ad breaks carry " + (d.adMode().equals("SGAI") ? "interstitial" : d.markerFormat().equals("CUE_OUT") ? "CUE-OUT/IN" : "SCTE-35 DATERANGE") + " markers",
                hasBreaks, hasBreaks ? "Markers are generated automatically for every break" : "No ad breaks scheduled — nothing to monetise"));
        boolean ok = checks.stream().allMatch(Check::passed);
        boolean simulated = d.kind().equals("PARTNER_TV");
        String msg = !ok ? "Not delivered: " + checks.stream().filter(c -> !c.passed()).map(Check::label).reduce((a, b) -> a + "; " + b).orElse("")
                : simulated ? "SIMULATED: feed passed our checks. No real partner was contacted — partner acceptance needs a contract and certification."
                : "Live in the web player (real prototype playout).";
        Integer version = ch.publishedVersionId() == null ? null : data.db.sql("select number from schedule_version where id = :v").param("v", ch.publishedVersionId()).query(Integer.class).single();
        UUID pid = data.db.sql("insert into publication(org_id, channel_id, destination_id, version_number, status, message, simulated) values (:o,:c,:d,:v,:s,:m,:sim) returning id")
                .param("o", t.orgId()).param("c", ch.id()).param("d", d.id()).param("v", version).param("s", ok ? "SUCCEEDED" : "FAILED")
                .param("m", msg).param("sim", simulated).query(UUID.class).single();
        if (!ok)
            data.db.sql("""
                    insert into alert(org_id, dedup_key, severity, title, detail, suggestion, link) values (:o,:k,'WARNING',:t,:d,:s,:l)
                    on conflict (org_id, dedup_key) where status <> 'RESOLVED' do update set detail = excluded.detail, updated_at = now()""")
                    .param("o", t.orgId()).param("k", "publish-failed:" + ch.id() + ":" + d.id())
                    .param("t", "Delivery to " + d.name() + " failed").param("d", ch.name() + ": " + msg)
                    .param("s", "Fix the failed checks, then publish again.").param("l", "/distribution").update();
        audit.log(t, "distribution.publish", "channel", ch.id(), d.name() + " → " + (ok ? "succeeded" : "failed") + (simulated ? " (simulated)" : ""));
        Publication p = new Publication(pid, ch.id(), ch.name(), d.id(), d.name(), version, ok ? "SUCCEEDED" : "FAILED", msg, simulated, OffsetDateTime.now());
        return new PublishResult(p, checks);
    }
}
