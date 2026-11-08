package com.faststudio.v1.util.cloudflare;

import com.faststudio.v1.core.common.Times;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Segment layout of streaming programmes and the numbering anchor (worker/src/ovp.test.ts checks the Worker's side). */
class TimelineTest {
    static final int[] MUX = {5000, 5000, 5000, 5000, 5000, 3857};   // 28.857 s

    static long listed(int[] seg, Timeline.Layout l) {
        long t = 0;
        for (int q = 0; q < l.nc(); q++) t += q == l.nc() - 1 && l.lastMs() > 0 ? l.lastMs() : seg[l.i0() + q];
        return t + Arrays.stream(l.fill()).sum();
    }

    @Test
    void wholeProgrammeInItsSlotEndsWithSlate() {
        Timeline.Layout l = Timeline.split(MUX, 0, 30_000);
        assertEquals(0, l.i0());
        assertEquals(6, l.nc());
        assertEquals(0, l.lastMs());
        assertArrayEquals(new int[]{1143}, l.fill());
        assertEquals(7, l.n());
        assertEquals(30_000, listed(MUX, l), "listed lengths always fill the slot exactly");
    }

    @Test
    void partsOfOneProgrammeShareBoundaries() {
        int[] seg = {6000, 6000, 6000, 6000, 6000};   // 30 s in 6 s segments, a break at 15 s
        Timeline.Layout a = Timeline.split(seg, 0, 15_000), b = Timeline.split(seg, 15_000, 15_000);
        assertEquals(2, a.nc(), "the first part stops at the boundary before its end (12 s)");
        assertArrayEquals(new int[]{3000}, a.fill());
        assertEquals(2, b.i0(), "the second part starts at that same boundary: nothing is lost or played twice");
        assertEquals(15_000, listed(seg, a));
        assertEquals(15_000, listed(seg, b));
    }

    @Test
    void slivers_are_absorbed_and_long_content_is_cut() {
        Timeline.Layout absorb = Timeline.split(new int[]{5000, 4800}, 0, 10_000);
        assertEquals(0, absorb.fill().length, "200 ms is not worth a segment");
        assertEquals(5000, absorb.lastMs());
        Timeline.Layout early = Timeline.split(new int[]{6000, 6000, 6000}, 0, 10_000);
        assertEquals(1, early.nc(), "a part ends at the last boundary before its end");
        assertArrayEquals(new int[]{4000}, early.fill());
        // a later part starts at the boundary before its offset, so its content can run past its window: the window cuts it
        Timeline.Layout cut = Timeline.split(new int[]{6000, 6000, 6000}, 3000, 10_000);
        assertEquals(2, cut.nc());
        assertEquals(4000, cut.lastMs(), "the window cuts the second segment");
        assertEquals(10_000, listed(new int[]{6000, 6000, 6000}, cut));
        Timeline.Layout past = Timeline.split(MUX, 40_000, 10_000);
        assertEquals(0, past.nc(), "an offset past the content is all slate");
        assertArrayEquals(new int[]{5000, 5000}, past.fill());
    }

    @Test
    void numberingAdvancesBySegmentsNotSlots() {
        long t = Times.EPOCH.toEpochMilli() + 3_600_000;
        long seq = Timeline.gridSeq(t);
        assertEquals(720, seq);
        Timeline.Span span = new Timeline.Span(t + 60_000, t + 90_000, Timeline.split(MUX, 0, 30_000));
        // 60 s of grid, a 6-slot programme with 7 segments, 30 s of grid
        assertEquals(seq + 12 + 7 + 6, Timeline.advance(seq, t, t + 120_000, List.of(span)));
        assertEquals(seq + 24, Timeline.advance(seq, t, t + 120_000, List.of()), "without streaming programmes the clock decides");
        assertTrue(Timeline.onGrid(t, t + 30_000));
        assertFalse(Timeline.onGrid(t, t + 28_857));
    }
}
