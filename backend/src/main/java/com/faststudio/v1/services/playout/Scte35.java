package com.faststudio.v1.services.playout;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Minimal ANSI/SCTE 35 splice_info_section encoder/decoder.
 * Encodes time_signal (0x06) + one segmentation_descriptor; decodes time_signal and splice_insert.
 * See docs/HOW_FAST_WORKS.md §4 for the field meanings.
 */
public final class Scte35 {
    private Scte35() {}

    public static final int PROVIDER_PO_START = 0x34, PROVIDER_PO_END = 0x35;
    public static final int DISTRIBUTOR_PO_START = 0x36, DISTRIBUTOR_PO_END = 0x37;
    public static final long PTS_MOD = 1L << 33;

    static final Map<Integer, String> SEG_TYPES = Map.ofEntries(
            Map.entry(0x00, "Not Indicated"), Map.entry(0x10, "Program Start"), Map.entry(0x11, "Program End"),
            Map.entry(0x22, "Break Start"), Map.entry(0x23, "Break End"),
            Map.entry(0x30, "Provider Advertisement Start"), Map.entry(0x31, "Provider Advertisement End"),
            Map.entry(0x32, "Distributor Advertisement Start"), Map.entry(0x33, "Distributor Advertisement End"),
            Map.entry(0x34, "Provider Placement Opportunity Start"), Map.entry(0x35, "Provider Placement Opportunity End"),
            Map.entry(0x36, "Distributor Placement Opportunity Start"), Map.entry(0x37, "Distributor Placement Opportunity End"));

    /** 90 kHz ticks, wrapped to 33 bits. */
    public static long ticks(long millis) {
        return Math.floorMod(millis * 90, PTS_MOD);
    }

    public static byte[] timeSignal(long ptsTicks, long eventId, int segType, Long durationTicks) {
        Bits cmd = new Bits();
        cmd.put(1, 1).put(0x3F, 6).put(ptsTicks, 33);                    // splice_time(): time_specified_flag, reserved, pts_time

        Bits d = new Bits();
        d.put(0x43554549L, 32);                                         // identifier "CUEI"
        d.put(eventId & 0xFFFFFFFFL, 32);
        d.put(0, 1).put(0x7F, 7);                                       // cancel=0, reserved
        d.put(1, 1).put(durationTicks != null ? 1 : 0, 1).put(1, 1).put(0x1F, 5); // program_seg, duration_flag, delivery_not_restricted
        if (durationTicks != null) d.put(durationTicks, 40);
        d.put(0x00, 8).put(0, 8);                                       // upid_type=not used, length 0
        d.put(segType, 8).put(0, 8).put(0, 8);                          // type, segment_num, segments_expected
        if (segType == 0x34 || segType == 0x36) d.put(0, 8).put(0, 8);  // sub_segment_num, sub_segments_expected
        byte[] desc = d.bytes();

        byte[] command = cmd.bytes();
        int loopLen = 2 + desc.length;
        int sectionLength = 1 + 5 + 1 + 3 + 1 + command.length + 2 + loopLen + 4;

        Bits s = new Bits();
        s.put(0xFC, 8);                                                 // table_id
        s.put(0, 1).put(0, 1).put(3, 2).put(sectionLength, 12);         // syntax ind., private ind., sap_type, section_length
        s.put(0, 8);                                                    // protocol_version
        s.put(0, 1).put(0, 6).put(0, 33);                               // encrypted, algorithm, pts_adjustment
        s.put(0, 8);                                                    // cw_index
        s.put(0xFFF, 12).put(command.length, 12);                       // tier, splice_command_length
        s.put(0x06, 8);                                                 // splice_command_type = time_signal
        s.raw(command);
        s.put(loopLen, 16);
        s.put(0x02, 8).put(desc.length, 8).raw(desc);                   // segmentation_descriptor
        byte[] body = s.bytes();
        long crc = crc32Mpeg2(body, 0, body.length);
        byte[] out = Arrays.copyOf(body, body.length + 4);
        for (int i = 0; i < 4; i++) out[body.length + i] = (byte) (crc >>> (24 - 8 * i));
        return out;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    /** CRC-32/MPEG-2: poly 0x04C11DB7, init 0xFFFFFFFF, not reflected, no final XOR. Not java.util.zip.CRC32. */
    public static long crc32Mpeg2(byte[] b, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 24;
            for (int k = 0; k < 8; k++) crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
        }
        return crc & 0xFFFFFFFFL;
    }

    public static byte[] parseInput(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
            byte[] out = new byte[s.length() / 2];
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
            return out;
        }
        return Base64.getDecoder().decode(s);
    }

    /** Human-readable decode for the SCTE-35 inspector. */
    public static Map<String, Object> decode(byte[] b) {
        Map<String, Object> m = new LinkedHashMap<>();
        Reader r = new Reader(b);
        int tableId = (int) r.get(8);
        if (tableId != 0xFC) throw new IllegalArgumentException("Not SCTE-35: table_id must be 0xFC");
        m.put("table_id", "0xFC");
        r.get(1); r.get(1);
        m.put("sap_type", r.get(2));
        int sectionLength = (int) r.get(12);
        m.put("section_length", sectionLength);
        m.put("protocol_version", r.get(8));
        boolean encrypted = r.get(1) == 1;
        r.get(6);
        m.put("encrypted", encrypted);
        m.put("pts_adjustment", r.get(33));
        r.get(8);
        m.put("tier", String.format("0x%03X", r.get(12)));
        int cmdLen = (int) r.get(12);
        int cmdType = (int) r.get(8);
        m.put("splice_command_type", String.format("0x%02X", cmdType));
        m.put("splice_command_name", switch (cmdType) {
            case 0x00 -> "splice_null";
            case 0x05 -> "splice_insert";
            case 0x06 -> "time_signal";
            case 0x07 -> "bandwidth_reservation";
            case 0xFF -> "private_command";
            default -> "other";
        });
        int cmdStart = r.pos / 8;
        if (cmdType == 0x06) {
            putTime(m, r);
        } else if (cmdType == 0x05) {
            m.put("splice_event_id", r.get(32));
            boolean cancel = r.get(1) == 1;
            r.get(7);
            if (!cancel) {
                boolean out = r.get(1) == 1, program = r.get(1) == 1, dur = r.get(1) == 1, immediate = r.get(1) == 1;
                r.get(4);
                m.put("out_of_network_indicator", out);
                if (program && !immediate) putTime(m, r);
                if (dur) {
                    m.put("auto_return", r.get(1) == 1);
                    r.get(6);
                    long d = r.get(33);
                    m.put("break_duration_ticks", d);
                    m.put("break_duration_seconds", d / 90000.0);
                }
                m.put("unique_program_id", r.get(16));
                m.put("avail_num", r.get(8));
                m.put("avails_expected", r.get(8));
            }
        }
        r.pos = (cmdStart + cmdLen) * 8;
        int loopLen = (int) r.get(16);
        int loopEnd = r.pos / 8 + loopLen;
        List<Map<String, Object>> descs = new ArrayList<>();
        while (r.pos / 8 < loopEnd) {
            int tag = (int) r.get(8), len = (int) r.get(8), start = r.pos / 8;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tag", String.format("0x%02X", tag));
            long ident = r.get(32);
            d.put("identifier", ident == 0x43554549L ? "CUEI" : String.format("0x%08X", ident));
            if (tag == 0x02) {
                d.put("segmentation_event_id", r.get(32));
                boolean cancel = r.get(1) == 1;
                r.get(7);
                if (!cancel) {
                    boolean program = r.get(1) == 1, dur = r.get(1) == 1, notRestricted = r.get(1) == 1;
                    r.get(5);
                    d.put("delivery_not_restricted", notRestricted);
                    if (!program) {
                        int n = (int) r.get(8);
                        r.pos += n * 48;
                    }
                    if (dur) {
                        long t = r.get(40);
                        d.put("segmentation_duration_ticks", t);
                        d.put("segmentation_duration_seconds", t / 90000.0);
                    }
                    d.put("upid_type", String.format("0x%02X", r.get(8)));
                    int upidLen = (int) r.get(8);
                    r.pos += upidLen * 8;
                    int type = (int) r.get(8);
                    d.put("segmentation_type_id", String.format("0x%02X", type));
                    d.put("segmentation_type", SEG_TYPES.getOrDefault(type, "Other"));
                    d.put("segment_num", r.get(8));
                    d.put("segments_expected", r.get(8));
                }
            }
            descs.add(d);
            r.pos = (start + len) * 8;
        }
        m.put("descriptors", descs);
        int total = 3 + sectionLength;
        long stored = 0;
        for (int i = total - 4; i < total; i++) stored = (stored << 8) | (b[i] & 0xFF);
        m.put("crc_32", String.format("0x%08X", stored));
        m.put("crc_valid", crc32Mpeg2(b, 0, total - 4) == stored);
        return m;
    }

    private static void putTime(Map<String, Object> m, Reader r) {
        boolean specified = r.get(1) == 1;
        if (specified) {
            r.get(6);
            long pts = r.get(33);
            m.put("pts_time", pts);
            m.put("pts_seconds", pts / 90000.0);
        } else r.get(7);
    }

    static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int acc, n;

        Bits put(long value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                acc = (acc << 1) | (int) ((value >>> i) & 1);
                if (++n == 8) { out.write(acc); acc = 0; n = 0; }
            }
            return this;
        }

        Bits raw(byte[] b) {
            for (byte x : b) put(x & 0xFF, 8);
            return this;
        }

        byte[] bytes() {
            if (n != 0) throw new IllegalStateException("not byte aligned");
            return out.toByteArray();
        }
    }

    static final class Reader {
        final byte[] b;
        int pos;

        Reader(byte[] b) { this.b = b; }

        long get(int bits) {
            long v = 0;
            for (int i = 0; i < bits; i++, pos++) v = (v << 1) | ((b[pos >> 3] >> (7 - (pos & 7))) & 1);
            return v;
        }
    }
}
