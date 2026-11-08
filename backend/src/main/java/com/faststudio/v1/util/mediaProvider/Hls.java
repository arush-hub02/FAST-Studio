package com.faststudio.v1.util.mediaProvider;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Just enough HLS playlist reading for adapters: the quality levels of a master playlist and the segments of a media playlist. */
public final class Hls {
    private Hls() {}

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    /** One quality level of a master playlist; uri is absolute. */
    public record Variant(int width, int height, int bandwidth, String uri) {}

    /** A VOD media playlist: real segment lengths in ms, fMP4 when it has an init section, complete when it has ENDLIST. */
    public record Media(int[] segmentsMs, boolean fmp4, boolean complete) {}

    public static List<Variant> master(String text, URI base) {
        List<Variant> out = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
            int w = 0, h = 0, bw = 0;
            Matcher m = ATTR.matcher(lines[i].substring(18));
            while (m.find()) {
                String v = m.group(2).replace("\"", "");
                switch (m.group(1)) {
                    case "BANDWIDTH" -> bw = parse(v);
                    case "RESOLUTION" -> {
                        String[] r = v.split("x");
                        if (r.length == 2) { w = parse(r[0]); h = parse(r[1]); }
                    }
                    default -> {}
                }
            }
            int j = i + 1;
            while (j < lines.length && (lines[j].isBlank() || lines[j].startsWith("#"))) j++;
            if (j < lines.length) out.add(new Variant(w, h, bw, base.resolve(lines[j].trim()).toString()));
            i = j;
        }
        return out;
    }

    public static Media media(String text) {
        List<Integer> d = new ArrayList<>();
        boolean fmp4 = false, end = false;
        for (String line : text.split("\\R")) {
            if (line.startsWith("#EXTINF:")) {
                String v = line.substring(8);
                int c = v.indexOf(',');
                d.add((int) Math.round(Double.parseDouble(c < 0 ? v : v.substring(0, c)) * 1000));
            } else if (line.startsWith("#EXT-X-MAP:")) fmp4 = true;
            else if (line.startsWith("#EXT-X-ENDLIST")) end = true;
        }
        return new Media(d.stream().mapToInt(Integer::intValue).toArray(), fmp4, end);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
