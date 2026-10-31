package com.faststudio.v1_0.services.playout;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Scte35Test {

    @Test
    void crcMatchesMpeg2CheckValue() {
        byte[] b = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x0376E6E7L, Scte35.crc32Mpeg2(b, 0, b.length));
    }

    @Test
    @SuppressWarnings("unchecked")
    void encodedTimeSignalRoundTrips() {
        long pts = Scte35.ticks(1_000_000_000_123L);
        byte[] b = Scte35.timeSignal(pts, 4242, Scte35.PROVIDER_PO_START, 90L * 90000);
        Map<String, Object> d = Scte35.decode(b);
        assertEquals(true, d.get("crc_valid"));
        assertEquals("time_signal", d.get("splice_command_name"));
        assertEquals(pts, d.get("pts_time"));
        var desc = ((List<Map<String, Object>>) d.get("descriptors")).getFirst();
        assertEquals("CUEI", desc.get("identifier"));
        assertEquals(4242L, desc.get("segmentation_event_id"));
        assertEquals("0x34", desc.get("segmentation_type_id"));
        assertEquals(90.0, desc.get("segmentation_duration_seconds"));
    }

    @Test
    void ptsWrapsAt33Bits() {
        long wrapMs = Scte35.PTS_MOD / 90;          // ~26.5 hours
        assertEquals(Scte35.ticks(1000), Scte35.ticks(wrapMs + 1000) + (Scte35.PTS_MOD - wrapMs * 90) % Scte35.PTS_MOD);
        assertTrue(Scte35.ticks(5 * wrapMs) < Scte35.PTS_MOD);
    }

    @Test
    @SuppressWarnings("unchecked")
    void decodesSpecSamplePlacementOpportunity() {
        // SCTE 35 sample: time_signal with Provider Placement Opportunity Start
        byte[] b = Scte35.parseInput("/DA0AAAAAAAA///wBQb+cr0AUAAeAhxDVUVJSAAAjn/PAAGlmbAICAAAAAAsoKGKNAIAmsnRfg==");
        Map<String, Object> d = Scte35.decode(b);
        assertEquals(true, d.get("crc_valid"));
        assertEquals("time_signal", d.get("splice_command_name"));
        var desc = ((List<Map<String, Object>>) d.get("descriptors")).getFirst();
        assertEquals("0x34", desc.get("segmentation_type_id"));
    }
}
