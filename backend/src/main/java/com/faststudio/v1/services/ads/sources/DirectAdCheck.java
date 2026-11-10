package com.faststudio.v1.services.ads.sources;

import com.faststudio.v1.core.common.PublicUrl;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an ad server's HLS offers, and whether it can play directly on a channel. Inside a break the edge swaps one ad
 * piece per 5 s step, so a direct ad must be cut into 5 s pieces like our R2 ads (a shorter last piece is dropped, as R2
 * ads drop their tail).
 */
@Component
public class DirectAdCheck {
    static final double SEG_S = 5.0, TOLERANCE_S = 0.1;
    static final long LENGTH_TOLERANCE_MS = 500;
    private static final JsonMapper JSON = new JsonMapper();
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    // Redirects are followed by PublicUrl.send, which checks every hop against private addresses.
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();

    /** container TS or FMP4; heights of the variants, in master order; pieces = durations (s) of the first variant. */
    public record Profile(String container, boolean separateAudio, List<Integer> heights, List<Double> pieces) {}

    /** Reads the ad's master (or single media playlist) and its first variant. */
    public Profile check(String masterUrl) throws IOException {
        return parse(get(masterUrl), masterUrl, u -> {
            try { return get(u); } catch (IOException e) { throw new IllegalStateException(e.getMessage()); }
        });
    }

    private String get(String url) throws IOException {
        if (!url.regionMatches(true, 0, "https://", 0, 8)) throw new IOException("not https");
        try {
            HttpResponse<String> res = PublicUrl.send(http, url, u -> HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode());
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** fetch reads a playlist by absolute URL (only the first variant is read). */
    static Profile parse(String master, String masterUrl, Function<String, String> fetch) {
        URI base = URI.create(masterUrl);
        if (!master.contains("#EXT-X-STREAM-INF")) return media(master, List.of(), false);
        List<Integer> heights = new ArrayList<>();
        String first = null;
        boolean audio = false;
        String[] lines = master.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#EXT-X-MEDIA:") && l.contains("TYPE=AUDIO") && URI_ATTR.matcher(l).find()) audio = true;
            if (l.startsWith("#EXT-X-STREAM-INF:")) {
                Matcher m = RESOLUTION.matcher(l);
                heights.add(m.find() ? Integer.parseInt(m.group(1)) : 0);
                for (int j = i + 1; j < lines.length && first == null; j++)
                    if (!lines[j].isBlank() && !lines[j].startsWith("#")) first = base.resolve(lines[j].trim()).toString();
            }
        }
        String variant = first == null ? null : fetch.apply(first);
        return media(variant == null ? "" : variant, heights, audio);
    }

    private static Profile media(String text, List<Integer> heights, boolean audio) {
        List<Double> pieces = new ArrayList<>();
        for (String l : text.split("\\r?\\n"))
            if (l.startsWith("#EXTINF:")) pieces.add(Double.parseDouble(l.substring(8).split(",")[0].trim()));
        return new Profile(text.contains("#EXT-X-MAP") ? "FMP4" : "TS", audio, heights, pieces);
    }

    /** Whole 5 s pieces × 5000 ms; a shorter last piece is dropped; 0 when any other piece isn't 5 s. */
    public static long slotMs(Profile p) {
        List<Double> d = p.pieces();
        int whole = 0;
        while (whole < d.size() && Math.abs(d.get(whole) - SEG_S) <= TOLERANCE_S) whole++;
        boolean tailOk = whole == d.size() || (whole == d.size() - 1 && d.getLast() < SEG_S - TOLERANCE_S);
        return tailOk ? whole * 5000L : 0;
    }

    /** null when the ad can play directly on a channel of this container with leftMs still open, else why not. */
    public static String misfit(Profile p, String channelFmt, long vastDurationMs, long leftMs) {
        double total = p.pieces().stream().mapToDouble(Double::doubleValue).sum();
        if (total < SEG_S - TOLERANCE_S) return "Direct not possible: shorter than one 5 s piece";
        long slot = slotMs(p);
        if (slot == 0) return "Direct not possible: pieces are not 5 s";
        if (!p.container().equals(channelFmt)) return "Direct not possible: " + p.container() + " pieces on a " + channelFmt + " channel";
        if ("FMP4".equals(channelFmt) && !p.separateAudio()) return "Direct not possible: no separate audio track";
        if (Math.abs(Math.round(total * 1000) - vastDurationMs) > LENGTH_TOLERANCE_MS) return "Direct not possible: length differs from the ad server's";
        if (slot > leftMs) return "Direct not possible: doesn't fit the time left";
        return null;
    }

    public static String toJson(Profile p) { return JSON.writeValueAsString(p); }

    public static Profile fromJson(String json) { return JSON.readValue(json, Profile.class); }
}
