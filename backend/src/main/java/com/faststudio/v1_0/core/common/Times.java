package com.faststudio.v1_0.core.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

public final class Times {
    private Times() {}

    /**
     * Every playout segment is exactly 5 s, measured from this fixed epoch (see docs/HOW_FAST_WORKS.md §2.1).
     * 5 s divides every standard spot length (15, 20, 30, 60 s), so an ad replaces slate segment for segment.
     */
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    public static final long SEG_MS = 5000;
    public static final int SEG_S = (int) (SEG_MS / 1000);

    public static OffsetDateTime utc(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    public static Instant snapDown(Instant i) {
        long ms = i.toEpochMilli() - EPOCH.toEpochMilli();
        return EPOCH.plusMillis(Math.floorDiv(ms, SEG_MS) * SEG_MS);
    }

    public static Instant snapUp(Instant i) {
        Instant d = snapDown(i);
        return d.equals(i) ? d : d.plusMillis(SEG_MS);
    }

    public static boolean aligned(Instant i) {
        return Math.floorMod(i.toEpochMilli() - EPOCH.toEpochMilli(), SEG_MS) == 0;
    }

    public static long snapMs(long ms) {
        return Math.round(ms / (double) SEG_MS) * SEG_MS;
    }

    /** Comma-separated upper-case codes, e.g. "IN,GB". */
    public static List<String> codes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).distinct().toList();
    }

    public static String csv(List<String> codes) {
        return codes == null ? "" : String.join(",", codes.stream().map(String::trim).map(String::toUpperCase).filter(s -> !s.isEmpty()).distinct().toList());
    }
}
