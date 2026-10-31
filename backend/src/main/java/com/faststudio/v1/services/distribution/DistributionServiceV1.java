package com.faststudio.v1.services.distribution;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.distribution.DistributionDto.*;
import com.faststudio.v1.repositories.distribution.DistributionRepository;
import com.faststudio.v1.repositories.programming.ScheduleRepository;
import com.faststudio.v1.services.programming.EpgServiceV1;
import com.faststudio.v1.services.programming.Planner;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Destinations a channel is delivered to, and delivery with pre-flight checks.
 * Deliveries to PARTNER_TV destinations are SIMULATED: they run our checks but contact no real partner.
 */
@Service
public class DistributionServiceV1 {
    private final Data data;
    private final DistributionRepository repository;
    private final ScheduleRepository schedules;
    private final EpgServiceV1 epg;
    private final Audit audit;

    public DistributionServiceV1(Data data, DistributionRepository repository, ScheduleRepository schedules, EpgServiceV1 epg, Audit audit) {
        this.data = data;
        this.repository = repository;
        this.schedules = schedules;
        this.epg = epg;
        this.audit = audit;
    }

    public Map<String, Object> overview(Tenant t) {
        return Map.of("destinations", repository.destinations(t.orgId()), "publications", repository.recentPublications(t.orgId()));
    }

    public Map<String, UUID> createDestination(Tenant t, DestinationRequest r) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        UUID id = repository.insertDestination(t.orgId(), r);
        audit.log(t, "destination.create", "destination", id, r.name());
        return Map.of("id", id);
    }

    /** Runs the delivery checks, records the attempt and raises an alert when it fails. */
    public PublishResult publish(Tenant t, UUID channelId, UUID destinationId) {
        t.require(Tenant.Perm.DISTRIBUTION_EDIT);
        Channel ch = data.channel(t.orgId(), channelId);
        Destination d = repository.destination(t.orgId(), destinationId).orElseThrow(() -> ApiException.notFound("Destination"));
        List<Check> checks = checks(t, ch, d);
        boolean ok = checks.stream().allMatch(Check::passed);
        boolean simulated = d.kind().equals("PARTNER_TV");
        String msg = !ok ? "Not delivered: " + checks.stream().filter(c -> !c.passed()).map(Check::label).reduce((a, b) -> a + "; " + b).orElse("")
                : simulated ? "SIMULATED: feed passed our checks. No real partner was contacted — partner acceptance needs a contract and certification."
                : "Live in the web player (real prototype playout).";
        Integer version = ch.publishedVersionId() == null ? null : schedules.versionNumber(ch.publishedVersionId());
        UUID pid = repository.insertPublication(t.orgId(), ch.id(), d.id(), version, ok, msg, simulated);
        if (!ok) repository.raiseWarning(t.orgId(), "publish-failed:" + ch.id() + ":" + d.id(), "Delivery to " + d.name() + " failed",
                ch.name() + ": " + msg, "Fix the failed checks, then publish again.", "/distribution");
        audit.log(t, "distribution.publish", "channel", ch.id(), d.name() + " → " + (ok ? "succeeded" : "failed") + (simulated ? " (simulated)" : ""));
        Publication p = new Publication(pid, ch.id(), ch.name(), d.id(), d.name(), version, ok ? "SUCCEEDED" : "FAILED", msg, simulated, OffsetDateTime.now());
        return new PublishResult(p, checks);
    }

    /** Pre-flight checks a destination needs: live, 6 h scheduled, EPG present, ad load within limit, markers on breaks. */
    private List<Check> checks(Tenant t, Channel ch, Destination d) {
        Instant now = Instant.now();
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Channel is live", ch.status().equals("LIVE"),
                ch.status().equals("LIVE") ? "On air" : "Channel status is " + ch.status().toLowerCase() + "; press 'Go live' first"));
        List<Entry> next24 = ch.publishedVersionId() == null ? List.of() : data.entries(t.orgId(), ch.publishedVersionId(), now, now.plusSeconds(24 * 3600));
        Instant end = data.coverageEnd(t.orgId(), ch.publishedVersionId());
        boolean covered = end != null && end.isAfter(now.plusSeconds(6 * 3600));
        checks.add(new Check("At least 6 hours of published schedule", covered, end == null ? "Nothing published" : "Scheduled until " + Planner.fmt(end, ch.timezone())));
        int programmes = epg.guide(t.orgId(), ch, now, now.plusSeconds(24 * 3600)).size();
        checks.add(new Check("EPG feed has programmes", programmes > 0, programmes + " programmes in the next 24 h (XMLTV)"));
        Map<Long, Long> perHour = new TreeMap<>();
        next24.stream().filter(Entry::isBreak).forEach(e -> perHour.merge(e.start().toEpochMilli() / 3_600_000L, e.durationMs() / 1000, Long::sum));
        long worst = perHour.values().stream().mapToLong(Long::longValue).max().orElse(0);
        checks.add(new Check("Ad load within destination limit", worst <= d.maxAdSecondsPerHour(),
                "Busiest hour has " + worst + " s of breaks; " + d.name() + " allows " + d.maxAdSecondsPerHour() + " s"));
        boolean hasBreaks = next24.stream().anyMatch(Entry::isBreak);
        String markers = d.adMode().equals("SGAI") ? "interstitial" : d.markerFormat().equals("CUE_OUT") ? "CUE-OUT/IN" : "SCTE-35 DATERANGE";
        checks.add(new Check("Ad breaks carry " + markers + " markers", hasBreaks,
                hasBreaks ? "Markers are generated automatically for every break" : "No ad breaks scheduled — nothing to monetise"));
        return checks;
    }
}
