package com.faststudio.v1.util.cloudflare;

import com.faststudio.v1.core.common.Times;

import java.util.ArrayList;
import java.util.List;

/**
 * Segment layout of programmes that stream from their platform (worker/src/playout.ts reads it from the snapshot).
 * Everything else stays on the 5 s grid. A streaming programme occupies whole 5 s slots in the schedule; inside them
 * the edge plays the platform's own segments at their real lengths and fills what is left with slate, so the
 * playlist's segment lengths always add up to the clock. Its segment count differs from its slot count, which is why
 * each channel keeps a numbering anchor (channel_timeline) instead of reading numbers off the clock.
 */
public final class Timeline {
    private Timeline() {}

    /** Leftovers shorter than this aren't worth a segment: the previous segment is listed that much longer instead. */
    static final int MIN_MS = 500;

    /**
     * One programme part in its schedule window: platform segments i0 .. i0+nc-1, the last one listed as lastMs long
     * (0 = its real length; shorter when the window cuts it, longer when it absorbs a sliver), then slate segments of
     * the given lengths. n = nc + fill.length.
     */
    public record Layout(int i0, int nc, int lastMs, int[] fill) {
        public int n() { return nc + fill.length; }

        /** Longest listed segment, for EXT-X-TARGETDURATION. */
        int longest(int[] seg) {
            int max = 0;
            for (int q = 0; q < nc; q++) max = Math.max(max, q == nc - 1 && lastMs > 0 ? lastMs : seg[i0 + q]);
            for (int f : fill) max = Math.max(max, f);
            return max;
        }
    }

    /**
     * The part that starts offsetMs into the video and lasts windowMs. It starts at the platform segment boundary at or
     * before its offset and ends at the boundary at or before its end, so consecutive parts of one programme share
     * boundaries and nothing plays twice (a part can lose up to one segment when its window is shorter than its content).
     */
    public static Layout split(int[] seg, long offsetMs, long windowMs) {
        int m = seg.length;
        long[] cum = new long[m + 1];
        for (int i = 0; i < m; i++) cum[i + 1] = cum[i] + seg[i];
        int i0 = floor(cum, offsetMs), j = Math.max(i0, floor(cum, offsetMs + windowMs));
        List<Integer> fill = new ArrayList<>();
        int nc = 0, last = 0;
        long t = 0;
        for (int i = i0; i < j && t < windowMs; i++) {
            nc++;
            if (t + seg[i] > windowMs) { last = (int) (windowMs - t); t = windowMs; } else t += seg[i];
        }
        long rest = windowMs - t;
        if (last > 0 && last < MIN_MS && nc > 1) {   // a cut sliver: drop it, the previous segment covers its time
            nc--;
            last = seg[i0 + nc - 1] + last;
        } else if (rest > 0 && rest < MIN_MS && nc > 0) {
            last = seg[i0 + nc - 1] + (int) rest;
        } else {
            while (rest > 0) {
                int f = (int) Math.min(rest, Times.SEG_MS);
                if (rest - f > 0 && rest - f < MIN_MS) f = (int) rest;
                fill.add(f);
                rest -= f;
            }
        }
        return new Layout(i0, nc, last, fill.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Largest i with cum[i] <= t (the boundary at or before t), capped at the last boundary. */
    private static int floor(long[] cum, long t) {
        int lo = 0, hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cum[mid] <= t) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** A streaming programme part placed in the schedule: [start, end) on the 5 s grid. */
    public record Span(long start, long end, Layout layout) {
        long slots() { return (end - start) / Times.SEG_MS; }
    }

    /** True when a part can be laid out (both ends on the grid); otherwise it plays standby slate on the grid. */
    public static boolean onGrid(long start, long end) {
        return end > start && Times.aligned(java.time.Instant.ofEpochMilli(start)) && Times.aligned(java.time.Instant.ofEpochMilli(end));
    }

    /** Sequence number of the grid point t when the clock alone decides it (channels without streaming programmes). */
    public static long gridSeq(long t) { return Math.floorDiv(t - Times.EPOCH.toEpochMilli(), Times.SEG_MS); }

    /**
     * Sequence number at grid point `to`, given the number at grid point `from` and the streaming parts between them
     * (each lies entirely inside [from, to)): grid slots count one each, a streaming part counts its own segments.
     */
    public static long advance(long fromSeq, long from, long to, List<Span> between) {
        long seq = fromSeq + (to - from) / Times.SEG_MS;
        for (Span s : between) if (s.start() >= from && s.end() <= to) seq += s.layout().n() - s.slots();
        return seq;
    }
}
