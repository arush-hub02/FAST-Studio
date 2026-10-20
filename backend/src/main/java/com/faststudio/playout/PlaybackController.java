package com.faststudio.playout;

import com.faststudio.ads.AdDecisionService;
import com.faststudio.core.ApiException;
import com.faststudio.core.Data;
import com.faststudio.core.TenantScope;
import com.faststudio.core.Data.Channel;
import com.faststudio.core.Data.Entry;
import com.faststudio.media.MediaService;
import com.faststudio.rights.Rights;
import com.faststudio.schedule.ScheduleController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Public viewer-side API (no admin session). Production would derive the viewer's country from a trusted
 * edge/geo-IP service and hand out signed, short-lived URLs; here the country is an explicit, labelled simulation.
 */
@RestController
@RequestMapping("/play")
public class PlaybackController {
    private static final MediaType HLS = MediaType.parseMediaType("application/vnd.apple.mpegurl");
    private final Data data;
    private final PlayoutService playout;
    private final MediaService media;

    public PlaybackController(Data data, PlayoutService playout, MediaService media) {
        this.data = data;
        this.playout = playout;
        this.media = media;
    }

    public record StartRequest(@NotBlank String org, @NotBlank String channel, UUID destinationId,
                               @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country) {}

    public record Started(UUID sessionId, String playlistUrl, String adMode, String markerFormat, String destination,
                          String country, boolean simulatedLocation, Rights.Decision rightsNow) {}

    record OrgRow(UUID id, String status) {}

    record DestRow(UUID id, String name, String adMode, String markerFormat) {}

    @PostMapping("/sessions")
    Started start(@Valid @RequestBody StartRequest r) {
        OrgRow org = data.db.sql("select id, status from organization where slug = :s").param("s", r.org()).query(OrgRow.class)
                .optional().orElseThrow(() -> ApiException.notFound("Channel"));
        TenantScope.set(org.id());          // everything below is read under this tenant's row-level security
        UUID chId = data.db.sql("select id from channel where org_id = :o and slug = :s").param("o", org.id()).param("s", r.channel())
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Channel"));
        Channel ch = data.channel(org.id(), chId);
        if (!"LIVE".equals(ch.status()))
            throw new ApiException(HttpStatus.CONFLICT, "NOT_LIVE", "\"" + ch.name() + "\" isn't on air. Publish a schedule and press 'Go live' first.");
        String sql = "select id, name, ad_mode, marker_format from destination where org_id = :o "
                + (r.destinationId() != null ? "and id = :d" : "and kind = 'OWNED_WEB' order by (ad_mode = 'SSAI') desc, created_at limit 1");
        var q = data.db.sql(sql).param("o", org.id());
        if (r.destinationId() != null) q = q.param("d", r.destinationId());
        DestRow d = q.query(DestRow.class).optional().orElseThrow(() -> ApiException.notFound("Destination"));
        String country = r.country().toUpperCase(Locale.ROOT);
        UUID sid = data.db.sql("insert into playback_session(org_id, channel_id, destination_id, country, ad_mode) values (:o,:c,:d,:k,:m) returning id")
                .param("o", org.id()).param("c", ch.id()).param("d", d.id()).param("k", country).param("m", d.adMode())
                .query(UUID.class).single();
        Rights.Decision now = currentRights(org.id(), ch, country);
        return new Started(sid, "/play/" + sid + "/live.m3u8", d.adMode(), d.markerFormat(), d.name(), country, true, now);
    }

    private Rights.Decision currentRights(UUID org, Channel ch, String country) {
        if (ch.publishedVersionId() == null) return null;
        Instant now = Instant.now();
        return data.entries(org, ch.publishedVersionId(), now, now.plusMillis(1)).stream().filter(e -> !e.isBreak()).findFirst()
                .map(e -> Rights.evaluate(data.policy(org, e.assetId()), ch.territoryList(), country, e.start(), e.end()))
                .orElse(null);
    }

    /** Master playlist (quality ladder). */
    @GetMapping("/{session}/live.m3u8")
    ResponseEntity<String> live(@PathVariable UUID session) {
        return ResponseEntity.ok().contentType(HLS).cacheControl(CacheControl.noStore()).body(playout.master(session));
    }

    /** Live media playlist of one rendition (0 = 360p … 2 = 720p). */
    @GetMapping("/{session}/v/{variant}.m3u8")
    ResponseEntity<String> variant(@PathVariable UUID session, @PathVariable int variant) {
        if (variant < 0 || variant >= MediaService.LADDER.size()) throw ApiException.notFound("Rendition");
        return ResponseEntity.ok().contentType(HLS).cacheControl(CacheControl.noStore()).body(playout.playlist(session, variant, Instant.now()));
    }

    @GetMapping("/{session}/ad/{breakId}/{slot}/{variant}/{n}.ts")
    ResponseEntity<FileSystemResource> adSegment(@PathVariable UUID session, @PathVariable UUID breakId, @PathVariable int slot,
                                                 @PathVariable int variant, @PathVariable int n) {
        String rel = playout.adSegment(playout.session(session), breakId, slot, variant, n);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp2t"))
                .body(new FileSystemResource(media.mediaDir().resolve(rel)));
    }

    @GetMapping("/{session}/asset-list/{breakId}.json")
    Map<String, Object> assetList(@PathVariable UUID session, @PathVariable UUID breakId) {
        return playout.assetList(playout.session(session), breakId);
    }

    @GetMapping("/{session}/slate/{seconds}.m3u8")
    ResponseEntity<String> slate(@PathVariable UUID session, @PathVariable int seconds) {
        playout.session(session);
        return ResponseEntity.ok().contentType(HLS).body(playout.slatePlaylist(seconds));
    }

    public record Beacon(UUID breakId, UUID campaignId, @NotBlank String event) {}

    /** Client-side beacons (SGAI): accepted only for ads the server actually decided for this session and break. */
    @PostMapping("/{session}/beacons")
    ResponseEntity<Void> beacon(@PathVariable UUID session, @RequestBody Beacon b) {
        var s = playout.session(session);
        if (!Set.of("IMPRESSION", "FIRST_QUARTILE", "MIDPOINT", "THIRD_QUARTILE", "COMPLETE").contains(b.event()))
            throw ApiException.bad("Unknown event");
        AdDecisionService.Decision d = playout.decision(s, playout.breakEntry(s, b.breakId()));
        var slot = d.slots().stream().filter(x -> x.campaignId().equals(b.campaignId())).findFirst()
                .orElseThrow(() -> ApiException.bad("That ad wasn't served in this break"));
        playout.recordEvent(s, b.breakId(), slot.campaignId(), b.event(), (int) (slot.durationMs() / 1000));
        return ResponseEntity.accepted().build();
    }

    // ------------------------------------------------------------------ what's on (for the player overlay)

    public record BreakInfo(UUID id, Instant start, Instant end, String ownership, boolean filledByUs, String scteOut,
                            AdDecisionService.Decision decision) {}

    public record EventRow(String event, String campaign, int seconds, java.time.OffsetDateTime occurredAt) {}

    public record Info(String channel, String channelStatus, String destination, String adMode, String markerFormat, String country,
                       ScheduleController.Programme now, ScheduleController.Programme next, BreakInfo currentBreak,
                       List<BreakInfo> upcomingBreaks, Rights.Decision rightsNow, List<EventRow> events, Instant serverTime) {}

    @GetMapping("/{session}/info")
    Info info(@PathVariable UUID session) {
        var s = playout.session(session);
        Channel ch = data.channel(s.orgId(), s.channelId());
        Instant now = Instant.now();
        var guide = ScheduleController.guide(data, s.orgId(), ch, now.minusSeconds(60), now.plusSeconds(6 * 3600));
        var cur = guide.stream().filter(ScheduleController.Programme::now).findFirst().orElse(null);
        var next = guide.stream().filter(p -> p.start().isAfter(now)).findFirst().orElse(null);
        List<Entry> breaks = ch.publishedVersionId() == null ? List.of()
                : data.entries(s.orgId(), ch.publishedVersionId(), now, now.plusSeconds(3 * 3600)).stream().filter(Entry::isBreak).toList();
        BreakInfo curBreak = null;
        List<BreakInfo> upcoming = new ArrayList<>();
        for (Entry b : breaks) {
            boolean ours = PlayoutService.ours(s, b) && ch.territoryList().contains(s.country());
            boolean active = !b.start().isAfter(now);
            AdDecisionService.Decision d = ours && !"PASSTHROUGH".equals(s.adMode()) && active ? playout.decision(s, b) : null;
            BreakInfo bi = new BreakInfo(b.id(), b.start(), b.end(), b.ownership(), ours && !"PASSTHROUGH".equals(s.adMode()),
                    PlayoutService.scteHex(b, true), d);
            if (active) curBreak = bi;
            else if (upcoming.size() < 3) upcoming.add(bi);
        }
        List<EventRow> events = data.db.sql("""
                select e.event, coalesce(c.name, 'Slate (unfilled)') as campaign, e.seconds, e.occurred_at
                from ad_event e left join campaign c on c.org_id = e.org_id and c.id = e.campaign_id
                where e.org_id = :o and e.session_id = :s order by e.id desc limit 12""")
                .param("o", s.orgId()).param("s", s.id()).query(EventRow.class).list();
        return new Info(ch.name(), ch.status(), s.destName(), s.adMode(), s.markerFormat(), s.country(), cur, next, curBreak, upcoming,
                currentRights(s.orgId(), ch, s.country()), events, now);
    }
}
