package com.faststudio.v1.services.programming;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Data.Asset;
import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;
import com.faststudio.v1.dto.programming.ScheduleDto.Programme;
import com.faststudio.v1.services.media.MediaSigner;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Electronic programme guide. Derived from the published version only, so the guide can never drift from what plays.
 * Used by the studio (EPG, channel cards) and by partners with the epg:read scope (XMLTV).
 */
@Service
public class EpgServiceV1 {
    private final Data data;
    private final MediaSigner signer;

    public EpgServiceV1(Data data, MediaSigner signer) {
        this.data = data;
        this.signer = signer;
    }

    /** Guide with signed thumbnail URLs, for the studio's EPG view. */
    public List<Programme> epgWithThumbnails(UUID org, Channel ch, Instant from, Instant to) {
        return guide(org, ch, from, to).stream()
                .map(p -> p.mediaKey() == null ? p : p.withThumb(signer.url(p.mediaKey(), "thumb.jpg"))).toList();
    }

    /** Programmes overlapping [from, to): consecutive parts of one video and the mid-roll breaks inside it are merged. */
    public List<Programme> guide(UUID org, Channel ch, Instant from, Instant to) {
        if (ch.publishedVersionId() == null) return List.of();
        Map<UUID, Asset> assets = data.assets(org);
        Instant now = Instant.now();
        List<Programme> out = new ArrayList<>();
        UUID cur = null;
        Instant cs = null, ce = null;
        int breaks = 0;
        for (Entry e : data.entries(org, ch.publishedVersionId(), from.minusSeconds(4 * 3600), to)) {
            boolean continues = cur != null && cur.equals(e.assetId()) && ce.equals(e.start())
                    && (e.isBreak() || e.assetOffsetMs() > 0);
            if (continues) {
                ce = e.end();
                if (e.isBreak()) breaks++;
                continue;
            }
            if (cur != null) out.add(programme(assets.get(cur), cs, ce, breaks, now));
            cur = null;
            if (!e.isBreak()) { cur = e.assetId(); cs = e.start(); ce = e.end(); breaks = 0; }
        }
        if (cur != null) out.add(programme(assets.get(cur), cs, ce, breaks, now));
        return out.stream().filter(p -> p.end().isAfter(from) && p.start().isBefore(to)).toList();
    }

    private static Programme programme(Asset a, Instant s, Instant e, int breaks, Instant now) {
        return new Programme(a.id(), a.title(), a.description(), a.genre(), a.kind(), a.color(), a.mediaKey(), s, e, breaks,
                !s.isAfter(now) && e.isAfter(now), null, a.series(), a.season(), a.episode());
    }

    /** XMLTV for the next 48 hours of the published schedule. */
    public String xmltv(UUID org, String orgSlug, Channel ch) {
        Instant now = Instant.now();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC);
        String cid = ch.slug() + "." + orgSlug;
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"FAST Studio prototype\">\n");
        x.append("  <channel id=\"").append(esc(cid)).append("\"><display-name>").append(esc(ch.name())).append("</display-name></channel>\n");
        for (Programme p : guide(org, ch, now.minusSeconds(3600), now.plusSeconds(48 * 3600))) {
            x.append("  <programme start=\"").append(f.format(p.start())).append("\" stop=\"").append(f.format(p.end()))
                    .append("\" channel=\"").append(esc(cid)).append("\">\n    <title lang=\"").append(esc(ch.language())).append("\">")
                    .append(esc(p.title())).append("</title>\n    <desc lang=\"").append(esc(ch.language())).append("\">").append(esc(p.description()))
                    .append("</desc>\n    <category lang=\"en\">").append(esc(p.genre())).append("</category>\n");
            if (p.season() != null && p.episode() != null && p.season() > 0 && p.episode() > 0)
                x.append("    <episode-num system=\"xmltv_ns\">").append(p.season() - 1).append('.').append(p.episode() - 1).append(".</episode-num>\n");
            x.append("  </programme>\n");
        }
        return x.append("</tv>\n").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
