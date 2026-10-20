package com.faststudio.schedule;

import com.faststudio.core.Data;
import com.faststudio.rights.Rights;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    static final OffsetDateTime T = NOW.atOffset(ZoneOffset.UTC);

    static Data.Channel channel(int adSecondsPerHour) {
        return new Data.Channel(UUID.randomUUID(), UUID.randomUUID(), "c", "C", "", "", "en", "UTC", "#000", "IN,GB", "DRAFT",
                adSecondsPerHour, 90, null, null, T, T);
    }

    static Data.Asset asset(long ms, String cues) {
        return new Data.Asset(UUID.randomUUID(), UUID.randomUUID(), "A", "", "MOVIE", "", "en", null, null, null, ms, cues, "READY", "", "k", "#000", T, T);
    }

    static Rights.Policy allow(Data.Asset a, boolean ads) {
        return new Rights.Policy(UUID.randomUUID(), a.id(), "IN,GB", "", null, null, ads, "");
    }

    static Data.Entry entry(String kind, UUID asset, Instant s, Instant e) {
        return new Data.Entry(UUID.randomUUID(), UUID.randomUUID(), kind, asset, 0, s.atOffset(ZoneOffset.UTC), e.atOffset(ZoneOffset.UTC), "PROVIDER");
    }

    static Set<String> codes(List<Planner.Issue> issues) {
        Set<String> s = new HashSet<>();
        issues.forEach(i -> s.add(i.code()));
        return s;
    }

    @Test
    void autofillRespectsHourlyAdLimitAndCuePoints() {
        var a = asset(300_000, "150000");
        var ch = channel(180);                       // 2 breaks of 90 s per hour
        var plan = Planner.autofill(ch, List.of(a), Map.of(a.id(), allow(a, true)), NOW, 3, Map.of());
        Map<Long, Long> perHour = new HashMap<>();
        plan.items().stream().filter(i -> i.kind().equals("AD_BREAK"))
                .forEach(i -> perHour.merge(i.start().toEpochMilli() / 3_600_000L, 90_000L, Long::sum));
        assertFalse(perHour.isEmpty());
        perHour.values().forEach(ms -> assertTrue(ms <= 180_000, "per-hour ad limit exceeded"));
        // generated plan validates clean
        var entries = plan.items().stream().map(i -> new Data.Entry(UUID.randomUUID(), null, i.kind(), i.assetId(), i.offsetMs(),
                i.start().atOffset(ZoneOffset.UTC), i.end().atOffset(ZoneOffset.UTC), i.ownership())).toList();
        var issues = Planner.validate(ch, entries, Map.of(a.id(), a), Map.of(a.id(), allow(a, true)), NOW);
        assertTrue(issues.stream().noneMatch(i -> i.severity().equals("ERROR")), issues.toString());
    }

    @Test
    void noAdsLicenceKeepsProgrammeWholeAndSkipsMidRolls() {
        var a = asset(300_000, "150000");
        var plan = Planner.autofill(channel(600), List.of(a), Map.of(a.id(), allow(a, false)), NOW, 1, Map.of());
        assertTrue(plan.items().stream().noneMatch(i -> i.kind().equals("AD_BREAK") && a.id().equals(i.assetId())));
        assertTrue(plan.items().stream().filter(i -> i.kind().equals("PROGRAM")).allMatch(i -> i.offsetMs() == 0));
    }

    @Test
    void validationCatchesOverlapRightsAndAdLoad() {
        var a = asset(600_000, "");
        var ch = channel(60);
        Instant s = NOW.plusSeconds(60);
        var es = List.of(
                entry("PROGRAM", a.id(), s, s.plusSeconds(300)),
                entry("PROGRAM", a.id(), s.plusSeconds(240), s.plusSeconds(480)),   // overlaps
                entry("AD_BREAK", null, s.plusSeconds(480), s.plusSeconds(570)));  // 90 s > 60 s/hour
        var issues = codes(Planner.validate(ch, es, Map.of(a.id(), a), Map.of(), NOW)); // no policy => rights error
        assertTrue(issues.containsAll(Set.of("OVERLAP", "RIGHTS", "AD_LOAD")), issues.toString());
    }
}
