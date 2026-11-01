package com.faststudio.v1.services.ads.sources;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.PublicUrl;
import com.faststudio.v1.dto.ads.AdsDto.Creative;
import com.faststudio.v1.repositories.ads.AdSourceRepository;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;
import com.faststudio.v1.services.media.MediaServiceV1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ad connector for any external ad server that speaks VAST (Google Ad Manager / IMA tags, SpringServe, FreeWheel,
 * a tenant's own tag). Fills in the tag's macros, follows wrappers, and maps each returned creative to a copy we
 * re-cut into 5 s segments. A creative seen for the first time can't air yet (conditioning takes seconds, the
 * decision window is about one): it is queued, this break falls through, and it plays from the next break on.
 */
@Component
public class VastSource implements IAdSource {
    private static final Logger log = LoggerFactory.getLogger(VastSource.class);
    static final int MAX_WRAPPERS = 4;
    /** VAST tracking event -> our delivery event. Impression pixels fire with IMPRESSION. */
    static final Map<String, String> EVENTS = Map.of("start", "IMPRESSION", "firstQuartile", "FIRST_QUARTILE",
            "midpoint", "MIDPOINT", "thirdQuartile", "THIRD_QUARTILE", "complete", "COMPLETE");

    private final AdSourceRepository repository;
    private final MediaServiceV1 media;
    // Redirects are followed by PublicUrl.send, which checks every hop against private addresses.
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();

    public VastSource(AdSourceRepository repository, MediaServiceV1 media) {
        this.repository = repository;
        this.media = media;
    }

    @Override
    public String kind() { return "VAST"; }

    /** One linear ad from a VAST response; tracking = our event -> URLs to call when it happens. */
    /** One linear ad. ref = its stable identity (creative URLs are often signed per request, so they can't be). */
    public record Ad(String title, String advertiser, long durationMs, String mediaUrl, String ref, Map<String, List<String>> tracking) {}

    @Override
    public Fill fill(Config src, Request req) {
        List<AdDecisionServiceV1.Slot> slots = new ArrayList<>();
        List<AdDecisionServiceV1.Excluded> excluded = new ArrayList<>();
        List<Ad> ads;
        try {
            ads = fetch(expand(src.tagUrl(), req), req.viewer(), System.nanoTime() + src.timeoutMs() * 1_000_000L, 0, Map.of());
        } catch (Exception e) {
            excluded.add(new AdDecisionServiceV1.Excluded(src.name(), "No answer: " + reason(e)));
            return new Fill(slots, excluded);
        }
        if (ads.isEmpty()) excluded.add(new AdDecisionServiceV1.Excluded(src.name(), "No fill: the ad server returned no playable ads"));
        long left = req.remainingMs();
        for (Ad ad : ads) {
            String label = src.name() + ": " + ad.title();
            Creative c = creative(req.org(), src, ad);
            if (c == null || !"READY".equals(c.status()) || c.mediaKey() == null)
                excluded.add(new AdDecisionServiceV1.Excluded(label, c != null && "FAILED".equals(c.status())
                        ? "The creative file couldn't be converted for playout"
                        : "Creative is being prepared for playout (first time seen); it airs from the next break"));
            else if (c.durationMs() > left)
                excluded.add(new AdDecisionServiceV1.Excluded(label, c.durationMs() / 1000 + " s ad doesn't fit the " + left / 1000 + " s left"));
            else if (!ad.advertiser().isEmpty() && req.advertisers().contains(ad.advertiser()))
                excluded.add(new AdDecisionServiceV1.Excluded(label, "Same advertiser already in this break (competitive separation)"));
            else {
                // the conditioned creative's id doubles as the "campaign" id in delivery events
                slots.add(new AdDecisionServiceV1.Slot(c.id(), label, ad.advertiser(), c.mediaKey(), c.durationMs(), null, src.name(), ad.tracking()));
                req.advertisers().add(ad.advertiser());
                left -= c.durationMs();
            }
        }
        return new Fill(slots, excluded);
    }

    /** Our copy of a creative, keyed by its ad id; queues conditioning the first time it is seen. */
    public Creative creative(UUID org, Config src, Ad ad) {
        Optional<Creative> found = repository.creative(org, ad.ref());
        if (found.isPresent()) return found.get();
        try {
            PublicUrl.check(ad.mediaUrl());
        } catch (ApiException e) {
            log.warn("Ignoring creative from {}: {}", src.name(), e.getMessage());
            return new Creative(null, "FAILED", null, 0);
        }
        repository.insertCreative(org, trim(ad.title(), 120), "Creative from " + src.name() + (ad.advertiser().isEmpty() ? "" : " (" + ad.advertiser() + ")"),
                ad.durationMs(), ad.mediaUrl(), ad.ref()).ifPresent(id -> media.enqueueCreative(org, id));
        return null;
    }

    // ------------------------------------------------------------------ VAST client

    /** Fetches a tag (and its wrappers) within the deadline. Used by the waterfall and the tag tester. */
    public List<Ad> fetch(String url, IAdSource.Viewer viewer, long deadlineNanos, int depth, Map<String, List<String>> inherited)
            throws IOException, InterruptedException {
        long ms = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (ms <= 0) throw new IOException("timed out");
        HttpResponse<byte[]> res = PublicUrl.send(http, url, u -> {
            var b = HttpRequest.newBuilder(u).timeout(Duration.ofMillis(ms)).GET();
            // server-side requests carry the viewer, as ad servers expect from a stitcher
            if (!viewer.userAgent().isEmpty()) b.header("User-Agent", viewer.userAgent());
            if (!viewer.ip().isEmpty()) b.header("X-Forwarded-For", viewer.ip());
            return b.build();
        }, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() == 204) return List.of();
        if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode());
        List<Ad> out = new ArrayList<>();
        for (Element ad : ads(parse(res.body()))) {
            Element inline = first(ad, "InLine"), wrapper = first(ad, "Wrapper");
            Map<String, List<String>> tracking = merge(inherited, tracking(inline != null ? inline : wrapper));
            if (wrapper != null && inline == null) {
                if (depth < MAX_WRAPPERS && !text(wrapper, "VASTAdTagURI").isEmpty())
                    out.addAll(fetch(text(wrapper, "VASTAdTagURI"), viewer, deadlineNanos, depth + 1, tracking));
                continue;
            }
            if (inline == null) continue;
            Element linear = first(inline, "Linear");
            String mediaUrl = linear == null ? null : pickMedia(linear);
            long dur = linear == null ? 0 : duration(text(linear, "Duration"));
            if (mediaUrl == null || dur <= 0) continue;   // not a playable linear video ad (e.g. VPAID only)
            String advertiser = !text(inline, "Advertiser").isEmpty() ? text(inline, "Advertiser") : text(inline, "AdSystem");
            out.add(new Ad(text(inline, "AdTitle").isEmpty() ? "Untitled ad" : text(inline, "AdTitle"), advertiser, dur, mediaUrl,
                    ref(ad, inline, linear, mediaUrl), tracking));
        }
        return out;
    }

    /** Stable creative identity: ad system + UniversalAdId, else the creative's ad id or id, else the ad id, else the file path. */
    static String ref(Element ad, Element inline, Element linear, String mediaUrl) {
        Element creative = linear.getParentNode() instanceof Element p && p.getTagName().equals("Creative") ? p : null;
        String uid = creative == null ? "" : text(creative, "UniversalAdId");
        String id = !uid.isEmpty() && !uid.equalsIgnoreCase("unknown") ? first(creative, "UniversalAdId").getAttribute("idRegistry") + ":" + uid
                : creative != null && !creative.getAttribute("AdID").isBlank() ? creative.getAttribute("AdID")
                : creative != null && !creative.getAttribute("adId").isBlank() ? creative.getAttribute("adId")
                : creative != null && !creative.getAttribute("id").isBlank() ? "creative:" + creative.getAttribute("id")
                : !ad.getAttribute("id").isBlank() ? "ad:" + ad.getAttribute("id")
                : mediaUrl.replaceAll("[?#].*$", "");
        return text(inline, "AdSystem") + "|" + id;
    }

    public static String expand(String tag, Request r) {
        Map<String, String> m = Map.of("[POD_DURATION]", String.valueOf(r.remainingMs() / 1000), "[CHANNEL_ID]", r.channel(),
                "[DEVICE_ID]", String.valueOf(r.sessionId()), "[CLIENT_IP]", r.viewer().ip(), "[USER_AGENT]", r.viewer().userAgent(),
                "[COUNTRY]", r.country(), "[CONTENT_GENRE]", r.genre(), "[CACHEBUSTER]", String.valueOf(ThreadLocalRandom.current().nextLong(1L << 40)),
                "[US_PRIVACY]", "", "[GDPR_CONSENT]", "");
        String out = tag;
        for (var e : m.entrySet()) out = out.replace(e.getKey(), URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        return out;
    }

    /**
     * Calls the ad server's tracking URLs for a delivered event (fire-and-forget: a count is never worth a stall).
     * Only the first address is called; a redirect it answers with is not followed (it could point anywhere).
     */
    public void track(AdDecisionServiceV1.Slot slot, String event) {
        for (String u : slot.tracking().getOrDefault(event, List.of())) {
            try {
                http.sendAsync(HttpRequest.newBuilder(PublicUrl.check(u)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
            } catch (ApiException e) {
                log.debug("Skipping a tracking URL blocked by outbound policy");
            }
        }
    }

    // ------------------------------------------------------------------ XML helpers

    static Document parse(byte[] xml) throws IOException {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // no XXE
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("not a VAST document (" + e.getMessage() + ")");
        }
    }

    /** Ad pods play in sequence order; an ad without a sequence comes after them. */
    static List<Element> ads(Document doc) {
        List<Element> ads = new ArrayList<>();
        for (Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals("Ad")) ads.add(e);
        ads.sort(Comparator.comparingInt(e -> e.getAttribute("sequence").isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(e.getAttribute("sequence").trim())));
        return ads;
    }

    static Map<String, List<String>> tracking(Element root) {
        Map<String, List<String>> t = new HashMap<>();
        NodeList imp = root.getElementsByTagName("Impression");
        for (int i = 0; i < imp.getLength(); i++) add(t, "IMPRESSION", imp.item(i).getTextContent());
        NodeList tr = root.getElementsByTagName("Tracking");
        for (int i = 0; i < tr.getLength(); i++) {
            String ev = EVENTS.get(((Element) tr.item(i)).getAttribute("event"));
            if (ev != null) add(t, ev, tr.item(i).getTextContent());
        }
        return t;
    }

    private static void add(Map<String, List<String>> t, String ev, String url) {
        if (url != null && !url.isBlank()) t.computeIfAbsent(ev, k -> new ArrayList<>()).add(url.trim());
    }

    static Map<String, List<String>> merge(Map<String, List<String>> a, Map<String, List<String>> b) {
        Map<String, List<String>> m = new HashMap<>();
        a.forEach((k, v) -> m.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
        b.forEach((k, v) -> m.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
        return m;
    }

    /** Progressive MP4 closest to our 720p top rendition (largest at or below 720 lines, else the smallest above). */
    static String pickMedia(Element linear) {
        NodeList files = linear.getElementsByTagName("MediaFile");
        Element best = null;
        int bestH = 0;
        for (int i = 0; i < files.getLength(); i++) {
            Element f = (Element) files.item(i);
            if (!f.getAttribute("type").contains("mp4") || f.getTextContent().isBlank()) continue;
            int h = f.getAttribute("height").isBlank() ? 0 : Integer.parseInt(f.getAttribute("height").trim());
            boolean better = best == null || (h <= 720 ? (bestH > 720 || h > bestH) : (bestH > 720 && h < bestH));
            if (better) { best = f; bestH = h; }
        }
        return best == null ? null : best.getTextContent().trim();
    }

    /** VAST duration HH:MM:SS(.mmm) in ms. */
    static long duration(String s) {
        if (s == null || !s.matches("\\d+:\\d{2}:\\d{2}(\\.\\d+)?")) return 0;
        String[] p = s.split(":");
        return Math.round((Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2])) * 1000);
    }

    static Element first(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    static String text(Element e, String tag) {
        Element c = first(e, tag);
        return c == null ? "" : c.getTextContent().trim();
    }

    public static String reason(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException || "timed out".equals(e.getMessage())) return "timed out";
        return e.getMessage() != null && e.getMessage().matches("HTTP [0-9]{3}") ? e.getMessage() : "provider request failed";
    }

    static String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
