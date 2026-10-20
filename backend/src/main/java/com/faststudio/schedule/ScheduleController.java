package com.faststudio.schedule;

import com.faststudio.core.*;
import com.faststudio.core.Data.Asset;
import com.faststudio.core.Data.Channel;
import com.faststudio.core.Data.Entry;
import com.faststudio.core.Data.Version;
import com.faststudio.media.MediaSigner;
import com.faststudio.rights.Rights;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Each channel has one editable draft (version 0) and immutable published versions 1..n.
 * Draft edits carry expectedRevision (optimistic concurrency): a stale editor gets 412 instead of overwriting.
 */
@RestController
@RequestMapping("/api/orgs/{org}/channels/{id}")
public class ScheduleController {
    private final Data data;
    private final Audit audit;
    private final MediaSigner signer;

    public ScheduleController(Data data, Audit audit, MediaSigner signer) {
        this.data = data;
        this.audit = audit;
        this.signer = signer;
    }

    public record AssetRef(UUID id, String title, String kind, String color, String mediaKey, long durationMs, String status) {}

    public record View(Data.Channel channel, Version draft, List<Entry> draftEntries, Version published, List<Entry> publishedEntries,
                       List<Planner.Issue> issues, List<Version> versions, Map<UUID, AssetRef> assets, boolean unpublishedChanges,
                       Instant now, Instant from, Instant to) {}

    @GetMapping("/schedule")
    View view(Tenant t, @PathVariable UUID id, @RequestParam(required = false) Instant from, @RequestParam(defaultValue = "26") int hours) {
        Channel ch = data.channel(t.orgId(), id);
        Instant now = Instant.now();
        Instant f = from != null ? from : now.minusSeconds(2 * 3600);
        Instant to = f.plusSeconds(Math.min(hours, 24 * 8) * 3600L);
        Version draft = data.draft(t.orgId(), id);
        List<Entry> allDraft = data.entries(t.orgId(), draft.id());
        Version pub = ch.publishedVersionId() == null ? null : data.versions(t.orgId(), id).stream()
                .filter(v -> v.id().equals(ch.publishedVersionId())).findFirst().orElse(null);
        Map<UUID, Asset> assets = data.assets(t.orgId());
        List<Planner.Issue> issues = Planner.validate(ch, allDraft, assets, data.policies(t.orgId()), now);
        List<Entry> pubAll = pub == null ? List.of() : data.entries(t.orgId(), pub.id());
        boolean changed = !signature(allDraft).equals(signature(pubAll));
        Map<UUID, AssetRef> refs = new HashMap<>();
        assets.values().forEach(a -> refs.put(a.id(), new AssetRef(a.id(), a.title(), a.kind(), a.color(), a.mediaKey(), a.durationMs(), a.status())));
        return new View(ch, draft, within(allDraft, f, to), pub, within(pubAll, f, to), issues,
                data.versions(t.orgId(), id), refs, changed, now, f, to);
    }

    static List<Entry> within(List<Entry> es, Instant f, Instant to) {
        return es.stream().filter(e -> e.end().isAfter(f) && e.start().isBefore(to)).toList();
    }

    static List<String> signature(List<Entry> es) {
        return es.stream().map(e -> e.kind() + e.assetId() + e.assetOffsetMs() + e.start() + e.end() + e.ownership()).toList();
    }

    // ------------------------------------------------------------------ draft edits

    public record AutofillRequest(int expectedRevision, Integer hours, List<UUID> assetIds, boolean replace) {}

    public record EditResult(int revision, List<String> notices) {}

    @PostMapping("/schedule/autofill")
    @Transactional
    EditResult autofill(Tenant t, @PathVariable UUID id, @RequestBody AutofillRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Channel ch = data.channel(t.orgId(), id);
        Version draft = bump(t, id, r.expectedRevision());
        Instant now = Instant.now();
        if (r.replace())
            data.db.sql("delete from schedule_entry where org_id = :o and version_id = :v and start_at >= :n")
                    .param("o", t.orgId()).param("v", draft.id()).param("n", Times.utc(Times.snapUp(now))).update();
        List<Entry> existing = data.entries(t.orgId(), draft.id());
        Instant from = existing.stream().map(Entry::end).filter(e -> e.isAfter(now)).max(Comparator.naturalOrder()).orElse(Times.snapUp(now.plusSeconds(3)));
        Map<Long, Long> adPerHour = new HashMap<>();
        existing.stream().filter(Entry::isBreak).forEach(e -> adPerHour.merge(Planner.hourOf(e.start()), e.durationMs(), Long::sum));
        Map<UUID, Asset> assets = data.assets(t.orgId());
        List<Asset> pool = r.assetIds() == null || r.assetIds().isEmpty()
                ? assets.values().stream().sorted(Comparator.comparing(Asset::createdAt)).toList()
                : r.assetIds().stream().map(assets::get).filter(Objects::nonNull).toList();
        int hours = Math.max(1, Math.min(r.hours() == null ? 24 : r.hours(), 72));
        Planner.Plan plan = Planner.autofill(ch, pool, data.policies(t.orgId()), from, hours, adPerHour);
        insert(t.orgId(), draft.id(), plan.items());
        audit.log(t, "schedule.autofill", "channel", id, hours + "h, " + plan.items().size() + " items");
        return new EditResult(draft.revision() + 1, plan.notices());
    }

    public record AddRequest(int expectedRevision, String kind, UUID assetId, Instant startAt, Integer seconds, Boolean withBreaks) {}

    @PostMapping("/schedule/entries")
    @Transactional
    EditResult add(Tenant t, @PathVariable UUID id, @RequestBody AddRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Channel ch = data.channel(t.orgId(), id);
        Version draft = bump(t, id, r.expectedRevision());
        List<Entry> existing = data.entries(t.orgId(), draft.id());
        Instant now = Instant.now();
        Instant start = r.startAt() != null ? Times.snapDown(r.startAt())
                : existing.stream().map(Entry::end).filter(e -> e.isAfter(now)).max(Comparator.naturalOrder()).orElse(Times.snapUp(now.plusSeconds(3)));
        List<Planner.Item> items;
        if ("AD_BREAK".equals(r.kind())) {
            int secs = (int) (Times.snapMs((r.seconds() == null ? ch.breakSeconds() : r.seconds()) * 1000L) / 1000);
            if (secs <= 0) throw ApiException.bad("Break length must be at least 3 seconds");
            UUID inside = existing.stream().filter(e -> !e.isBreak() && e.end().equals(start)).map(Entry::assetId).findFirst().orElse(null);
            items = List.of(new Planner.Item("AD_BREAK", inside, 0, start, start.plusSeconds(secs), "PROVIDER"));
        } else {
            Asset a = data.asset(t.orgId(), r.assetId());
            if (!a.ready()) throw ApiException.bad("\"" + a.title() + "\" isn't ready yet, so it can't be scheduled.");
            if (a.kind().equals("AD") || a.kind().equals("SLATE")) throw ApiException.bad("Ads are placed by the ad server inside ad breaks, not scheduled as programmes.");
            if (Boolean.FALSE.equals(r.withBreaks())) {
                items = List.of(new Planner.Item("PROGRAM", a.id(), 0, start, start.plusMillis(a.durationMs()), null));
            } else {
                Map<Long, Long> adPerHour = new HashMap<>();
                existing.stream().filter(Entry::isBreak).forEach(e -> adPerHour.merge(Planner.hourOf(e.start()), e.durationMs(), Long::sum));
                // a one-programme auto-fill; keep only that programme and its mid-rolls
                items = new ArrayList<>();
                for (Planner.Item it : Planner.autofill(ch, List.of(a), withPolicy(t, a), start, 1, adPerHour).items()) {
                    if (it.assetId() == null) break;      // stop at the between-programme break
                    items.add(it);
                    if (!it.kind().equals("AD_BREAK") && it.offsetMs() + (it.end().toEpochMilli() - it.start().toEpochMilli()) >= a.durationMs()) break;
                }
                if (items.isEmpty()) items = List.of(new Planner.Item("PROGRAM", a.id(), 0, start, start.plusMillis(a.durationMs()), null));
            }
        }
        insert(t.orgId(), draft.id(), items);
        audit.log(t, "schedule.add", "channel", id, r.kind() + " at " + start);
        return new EditResult(draft.revision() + 1, List.of("Added at " + Planner.fmt(start, ch.timezone()) + ". Check the issues list before publishing."));
    }

    /** Rights are reported by validation, not silently enforced by the manual "add" action. */
    private Map<UUID, Rights.Policy> withPolicy(Tenant t, Asset a) {
        return Map.of(a.id(), alwaysAllowed(data.policy(t.orgId(), a.id())));
    }

    private static Rights.Policy alwaysAllowed(Rights.Policy p) {
        return new Rights.Policy(null, p == null ? null : p.assetId(), "WW", "", null, null, p == null || p.adsAllowed(), "");
    }

    @DeleteMapping("/schedule/entries/{entryId}")
    @Transactional
    EditResult remove(Tenant t, @PathVariable UUID id, @PathVariable UUID entryId, @RequestParam int expectedRevision,
                      @RequestParam(defaultValue = "true") boolean wholeProgramme) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, expectedRevision);
        List<Entry> es = data.entries(t.orgId(), draft.id());
        int idx = -1;
        for (int i = 0; i < es.size(); i++) if (es.get(i).id().equals(entryId)) idx = i;
        if (idx < 0) throw ApiException.notFound("Schedule item");
        Set<UUID> doomed = new HashSet<>(List.of(entryId));
        Entry target = es.get(idx);
        if (wholeProgramme && target.assetId() != null) {
            // walk the contiguous placement: parts of the same video and the mid-roll breaks inside it
            for (int i = idx - 1; i >= 0 && sameRun(es.get(i), es.get(i + 1), target.assetId()); i--) doomed.add(es.get(i).id());
            for (int i = idx + 1; i < es.size() && sameRun(es.get(i - 1), es.get(i), target.assetId()); i++) doomed.add(es.get(i).id());
        }
        data.db.sql("delete from schedule_entry where org_id = :o and version_id = :v and id in (:ids)")
                .param("o", t.orgId()).param("v", draft.id()).param("ids", doomed).update();
        audit.log(t, "schedule.remove", "channel", id, doomed.size() + " item(s)");
        return new EditResult(draft.revision() + 1, List.of("Removed " + doomed.size() + " item(s). Use 'Close gaps' to pull later items forward."));
    }

    private static boolean sameRun(Entry a, Entry b, UUID asset) {
        return a.end().equals(b.start()) && asset.equals(a.assetId()) && asset.equals(b.assetId())
                && !(b.kind().equals("PROGRAM") && b.assetOffsetMs() == 0);
    }

    public record RevisionRequest(int expectedRevision) {}

    @PostMapping("/schedule/close-gaps")
    @Transactional
    EditResult closeGaps(Tenant t, @PathVariable UUID id, @RequestBody RevisionRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, r.expectedRevision());
        Instant now = Instant.now();
        Instant cursor = null;
        int moved = 0;
        for (Entry e : data.entries(t.orgId(), draft.id())) {
            if (!e.start().isAfter(now)) { cursor = e.end(); continue; }   // never move what is already on air
            Instant newStart = cursor == null || cursor.isBefore(now) ? Times.snapUp(now.plusSeconds(3)) : cursor;
            if (newStart.isBefore(e.start())) {
                data.db.sql("update schedule_entry set start_at = :s, end_at = :e where id = :id and org_id = :o")
                        .param("s", Times.utc(newStart)).param("e", Times.utc(newStart.plusMillis(e.durationMs())))
                        .param("id", e.id()).param("o", t.orgId()).update();
                moved++;
                cursor = newStart.plusMillis(e.durationMs());
            } else cursor = e.end().isAfter(cursor == null ? e.end() : cursor) ? e.end() : cursor;
        }
        audit.log(t, "schedule.close_gaps", "channel", id, moved + " moved");
        return new EditResult(draft.revision() + 1, List.of(moved == 0 ? "No gaps to close." : "Moved " + moved + " item(s) earlier."));
    }

    @PostMapping("/schedule/clear")
    @Transactional
    EditResult clear(Tenant t, @PathVariable UUID id, @RequestBody RevisionRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, r.expectedRevision());
        int n = data.db.sql("delete from schedule_entry where org_id = :o and version_id = :v and start_at > :n")
                .param("o", t.orgId()).param("v", draft.id()).param("n", Times.utc(Instant.now())).update();
        audit.log(t, "schedule.clear", "channel", id, n + " future items");
        return new EditResult(draft.revision() + 1, List.of("Cleared " + n + " upcoming item(s). What's on air now is untouched."));
    }

    public record RestoreRequest(int expectedRevision, int versionNumber) {}

    @PostMapping("/schedule/restore")
    @Transactional
    EditResult restore(Tenant t, @PathVariable UUID id, @RequestBody RestoreRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, r.expectedRevision());
        Version v = data.versions(t.orgId(), id).stream().filter(x -> x.number() == r.versionNumber()).findFirst()
                .orElseThrow(() -> ApiException.notFound("Version"));
        data.db.sql("delete from schedule_entry where org_id = :o and version_id = :v").param("o", t.orgId()).param("v", draft.id()).update();
        copy(t.orgId(), v.id(), draft.id());
        audit.log(t, "schedule.restore", "channel", id, "from v" + v.number());
        return new EditResult(draft.revision() + 1, List.of("Draft now matches version " + v.number() + ". Publish it to make it live again (rollback = new version)."));
    }

    // ------------------------------------------------------------------ publish

    public record PublishRequest(int expectedRevision, String note) {}

    public record PublishResult(int versionNumber, List<Planner.Issue> warnings) {}

    @PostMapping("/schedule/publish")
    @Transactional
    PublishResult publish(Tenant t, @PathVariable UUID id, @RequestBody PublishRequest r) {
        t.require(Tenant.Perm.SCHEDULE_PUBLISH);
        // serialise publications per channel
        data.db.sql("select id from channel where org_id = :o and id = :id for update").param("o", t.orgId()).param("id", id).query(UUID.class).single();
        Channel ch = data.channel(t.orgId(), id);
        Version draft = data.draft(t.orgId(), id);
        if (draft.revision() != r.expectedRevision())
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE", "Someone changed this schedule since you loaded it. Reload to see their changes, then publish again.");
        List<Planner.Issue> issues = Planner.validate(ch, data.entries(t.orgId(), draft.id()), data.assets(t.orgId()), data.policies(t.orgId()), Instant.now());
        List<Planner.Issue> errors = issues.stream().filter(i -> i.severity().equals("ERROR")).toList();
        if (!errors.isEmpty())
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SCHEDULE_INVALID", "Fix " + errors.size() + " problem(s) before publishing.", errors);
        int next = data.db.sql("select coalesce(max(number), 0) + 1 from schedule_version where channel_id = :c").param("c", id).query(Integer.class).single();
        UUID vid = data.db.sql("insert into schedule_version(org_id, channel_id, number, status, note, created_by, published_at) values (:o,:c,:n,'PUBLISHED',:note,:u,now()) returning id")
                .param("o", t.orgId()).param("c", id).param("n", next).param("note", r.note() == null ? "" : r.note()).param("u", t.userId())
                .query(UUID.class).single();
        copy(t.orgId(), draft.id(), vid);
        data.db.sql("update schedule_version set status = 'SUPERSEDED' where org_id = :o and channel_id = :c and status = 'PUBLISHED' and id <> :v")
                .param("o", t.orgId()).param("c", id).param("v", vid).update();
        data.db.sql("update channel set published_version_id = :v, updated_at = now() where org_id = :o and id = :c")
                .param("v", vid).param("o", t.orgId()).param("c", id).update();
        audit.log(t, "schedule.publish", "channel", id, "version " + next + (r.note() == null || r.note().isBlank() ? "" : ": " + r.note()));
        return new PublishResult(next, issues);
    }

    // ------------------------------------------------------------------ EPG

    public record Programme(UUID assetId, String title, String description, String genre, String kind, String color, String mediaKey,
                            Instant start, Instant end, int adBreaks, boolean now, String thumbUrl) {
        Programme withThumb(String url) {
            return new Programme(assetId, title, description, genre, kind, color, mediaKey, start, end, adBreaks, now, url);
        }
    }

    @GetMapping("/epg")
    List<Programme> epg(Tenant t, @PathVariable UUID id, @RequestParam(required = false) Instant from, @RequestParam(defaultValue = "24") int hours) {
        Channel ch = data.channel(t.orgId(), id);
        Instant f = from == null ? Instant.now().minusSeconds(3600) : from;
        return guide(data, t.orgId(), ch, f, f.plusSeconds(Math.min(hours, 168) * 3600L)).stream()
                .map(p -> p.mediaKey() == null ? p : p.withThumb(signer.url(p.mediaKey(), "thumb.jpg"))).toList();
    }

    /** EPG is derived from the published version only, so the guide can never drift from what plays. */
    public static List<Programme> guide(Data data, UUID org, Channel ch, Instant from, Instant to) {
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
            if (cur != null) out.add(prog(assets.get(cur), cs, ce, breaks, now));
            cur = null;
            if (!e.isBreak()) { cur = e.assetId(); cs = e.start(); ce = e.end(); breaks = 0; }
        }
        if (cur != null) out.add(prog(assets.get(cur), cs, ce, breaks, now));
        return out.stream().filter(p -> p.end().isAfter(from) && p.start().isBefore(to)).toList();
    }

    private static Programme prog(Asset a, Instant s, Instant e, int breaks, Instant now) {
        return new Programme(a.id(), a.title(), a.description(), a.genre(), a.kind(), a.color(), a.mediaKey(), s, e, breaks,
                !s.isAfter(now) && e.isAfter(now), null);
    }

    @GetMapping(value = "/epg.xml", produces = MediaType.APPLICATION_XML_VALUE)
    String xmltv(Tenant t, @PathVariable UUID id) {
        return xmltv(data, t.orgId(), t.slug(), data.channel(t.orgId(), id));
    }

    /** XMLTV for the next 48 hours of the published schedule (also served to partners with epg:read). */
    public static String xmltv(Data data, UUID org, String orgSlug, Channel ch) {
        Instant now = Instant.now();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC);
        String cid = ch.slug() + "." + orgSlug;
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"FAST Studio prototype\">\n");
        x.append("  <channel id=\"").append(esc(cid)).append("\"><display-name>").append(esc(ch.name())).append("</display-name></channel>\n");
        for (Programme p : guide(data, org, ch, now.minusSeconds(3600), now.plusSeconds(48 * 3600))) {
            x.append("  <programme start=\"").append(f.format(p.start())).append("\" stop=\"").append(f.format(p.end()))
                    .append("\" channel=\"").append(esc(cid)).append("\">\n    <title lang=\"").append(esc(ch.language())).append("\">")
                    .append(esc(p.title())).append("</title>\n    <desc lang=\"").append(esc(ch.language())).append("\">").append(esc(p.description()))
                    .append("</desc>\n    <category lang=\"en\">").append(esc(p.genre())).append("</category>\n  </programme>\n");
        }
        return x.append("</tv>\n").toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ helpers

    private Version bump(Tenant t, UUID channelId, int expected) {
        data.channel(t.orgId(), channelId);
        Version d = data.draft(t.orgId(), channelId);
        int n = data.db.sql("update schedule_version set revision = revision + 1 where org_id = :o and id = :v and revision = :r")
                .param("o", t.orgId()).param("v", d.id()).param("r", expected).update();
        if (n == 0) throw new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE",
                "Someone changed this schedule since you loaded it. Your view has been refreshed; please try again.");
        return d;
    }

    void insert(UUID org, UUID versionId, List<Planner.Item> items) {
        for (Planner.Item it : items)
            data.db.sql("insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership) values (:o,:v,:k,:a,:off,:s,:e,:own)")
                    .param("o", org).param("v", versionId).param("k", it.kind()).param("a", it.assetId()).param("off", it.offsetMs())
                    .param("s", Times.utc(it.start())).param("e", Times.utc(it.end())).param("own", it.ownership()).update();
    }

    private void copy(UUID org, UUID fromVersion, UUID toVersion) {
        data.db.sql("""
                insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership)
                select org_id, :to, kind, asset_id, asset_offset_ms, start_at, end_at, ownership from schedule_entry
                where org_id = :o and version_id = :from""").param("to", toVersion).param("o", org).param("from", fromVersion).update();
    }
}
