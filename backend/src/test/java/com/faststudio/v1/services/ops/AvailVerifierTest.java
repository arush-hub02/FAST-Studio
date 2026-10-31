package com.faststudio.v1.services.ops;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AvailVerifierTest {
    static final Instant B = Instant.parse("2026-10-29T10:01:00Z");   // the break's start

    /** A 60 s live playlist from `first`, shaped like the Worker's: tags.get(t) go before the segment at t. */
    static String window(Instant first, Map<Instant, String> tags) {
        StringBuilder m = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:5\n#EXT-X-PROGRAM-DATE-TIME:" + first + "\n");
        for (int i = 0; i < 12; i++)
            m.append(tags.getOrDefault(first.plusSeconds(5L * i), "")).append("#EXTINF:5.000,\nhttps://m/k/0/seg_0000").append(i % 10).append(".ts\n");
        return m.toString();
    }

    /** CUE-OUT/CUE-IN markers as the Worker writes them for a break of `secs` from B. */
    static Map<Instant, String> cues(int secs) {
        Map<Instant, String> m = new HashMap<>();
        m.put(B, "#EXT-X-CUE-OUT:DURATION=" + secs + ".000\n");
        for (int s = 5; s < secs; s += 5) m.put(B.plusSeconds(s), "#EXT-X-CUE-OUT-CONT:ElapsedTime=" + s + ".000,Duration=" + secs + ".000\n");
        m.put(B.plusSeconds(secs), "#EXT-X-CUE-IN\n");
        return m;
    }

    static String out(String id, String duration) {
        return "#EXT-X-DATERANGE:ID=\"" + id + "\",START-DATE=\"" + B + "\",PLANNED-DURATION=" + duration + ",SCTE35-OUT=0xFC30\n";
    }

    static String in(String id) {
        return "#EXT-X-DATERANGE:ID=\"" + id + "\",START-DATE=\"" + B + "\",DURATION=30.000,SCTE35-IN=0xFC31\n";
    }

    static AvailVerifier.Check check(String markers, int secs, String... playlists) {
        return AvailVerifier.check(List.of(playlists), markers, B, B.plusSeconds(secs));
    }

    static void fails(AvailVerifier.Check c, String expected) {
        assertTrue(c.problems().stream().anyMatch(p -> p.contains(expected)), "expected '" + expected + "' in " + c.problems());
    }

    @Test
    void aBreakSeenWholeInOneWindowPasses() {
        var c = check("CUE_OUT", 30, window(B.minusSeconds(10), cues(30)));
        assertTrue(c.complete());
        assertEquals(List.of(), c.problems());
    }

    @Test
    void aLongBreakIsJudgedAcrossOverlappingWindows() {
        var tags = cues(120);
        var first = window(B.minusSeconds(10), tags);
        assertEquals(new AvailVerifier.Check(false, List.of()), check("CUE_OUT", 120, first));   // still on air: no verdict yet
        String[] all = new String[6];
        for (int i = 0; i < 6; i++) all[i] = window(B.plusSeconds(-10 + 20L * i), tags);       // a fetch every 20 s
        var c = check("CUE_OUT", 120, all);
        assertTrue(c.complete());
        assertEquals(List.of(), c.problems());
    }

    @Test
    void aMissedFetchLeavesTheBreakUnjudged() {
        var tags = cues(120);
        var c = check("CUE_OUT", 120, window(B.minusSeconds(10), tags), window(B.plusSeconds(90), tags));   // B+50..B+90 never seen
        assertEquals(new AvailVerifier.Check(false, List.of()), c);
    }

    @Test
    void joiningMidBreakIsNotAFailure() {
        var tags = cues(120);
        var c = check("CUE_OUT", 120, window(B.plusSeconds(20), tags), window(B.plusSeconds(40), tags), window(B.plusSeconds(70), tags));
        assertFalse(c.complete());
        assertEquals(List.of(), c.problems());
    }

    @Test
    void aBreakWithoutMarkersIsTheSilentFailure() {
        var c = check("CUE_OUT", 30, window(B.minusSeconds(10), Map.of()));
        assertTrue(c.complete());
        fails(c, "No CUE_OUT marker at the break's start");
    }

    @Test
    void theDestinationMustGetItsOwnMarkerFormat() {
        var c = check("CUE_OUT", 30, window(B.minusSeconds(10), Map.of(B, out("b1", "30.000"), B.plusSeconds(30), in("b1"))));
        fails(c, "No CUE_OUT marker");
    }

    @Test
    void everyCueOutNeedsACueIn() {
        var tags = cues(30);
        tags.remove(B.plusSeconds(30));
        fails(check("CUE_OUT", 30, window(B.minusSeconds(10), tags)), "No CUE-IN where the avail from " + B);
        fails(check("CUE_OUT", 30, window(B.minusSeconds(10), Map.of(B.plusSeconds(30), "#EXT-X-CUE-IN\n"))), "without a CUE-OUT");
    }

    @Test
    void markersCarryDurationsAndNoAvailIsZeroLength() {
        fails(check("CUE_OUT", 30, window(B.minusSeconds(10), Map.of(B, "#EXT-X-CUE-OUT\n"))), "carries no duration");
        var zero = check("CUE_OUT", 30, window(B.minusSeconds(10), Map.of(B, "#EXT-X-CUE-OUT:DURATION=0.000\n#EXT-X-CUE-IN\n")));
        fails(zero, "zero-length avail");
        fails(zero, "CUE-OUT and CUE-IN on one segment");
        fails(check("DATERANGE", 30, window(B.minusSeconds(10), Map.of(B, out("b1", "0.000")))), "zero-length avail");
    }

    @Test
    void dateRangeOutsAreClosedByAnInWithTheirId() {
        var ok = check("DATERANGE", 30, window(B.minusSeconds(10), Map.of(B, out("b1", "30.000"), B.plusSeconds(30), in("b1"))));
        assertTrue(ok.complete());
        assertEquals(List.of(), ok.problems());
        fails(check("DATERANGE", 30, window(B.minusSeconds(10), Map.of(B, out("b1", "30.000")))), "DATERANGE b1 SCTE35-OUT from " + B + " has no SCTE35-IN");
        var wrongId = check("DATERANGE", 30, window(B.minusSeconds(10), Map.of(B, out("b1", "30.000"), B.plusSeconds(30), in("b2"))));
        fails(wrongId, "b1 SCTE35-OUT from");
        fails(wrongId, "DATERANGE b2 SCTE35-IN has no SCTE35-OUT");
    }

    @Test
    void sgaiInterstitialsNeedADuration() {
        String ad = "#EXT-X-DATERANGE:ID=\"ad-b1\",CLASS=\"com.apple.hls.interstitial\",START-DATE=\"" + B
                + "\",DURATION=30.000,X-ASSET-LIST=\"asset-list/b1.json\",X-RESUME-OFFSET=30.000,X-RESTRICT=\"SKIP,JUMP\"\n";
        assertEquals(List.of(), check("INTERSTITIAL", 30, window(B.minusSeconds(10), Map.of(B, ad))).problems());
        fails(check("INTERSTITIAL", 30, window(B.minusSeconds(10), Map.of(B, ad.replace("DURATION=30.000,X-ASSET", "X-ASSET")))), "Interstitial ad-b1 carries no duration");
    }
}
