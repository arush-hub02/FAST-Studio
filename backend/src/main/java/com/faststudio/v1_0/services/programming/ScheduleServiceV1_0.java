package com.faststudio.v1_0.services.programming;

import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.common.Data.Asset;
import com.faststudio.v1_0.core.common.Data.Channel;
import com.faststudio.v1_0.core.common.Data.Entry;
import com.faststudio.v1_0.core.common.Data.Version;
import com.faststudio.v1_0.core.common.Times;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.dto.programming.ScheduleDto.*;
import com.faststudio.v1_0.repositories.programming.ScheduleRepository;
import com.faststudio.v1_0.services.media.MediaSettingsServiceV1_0;
import com.faststudio.v1_0.services.rights.Rights;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Editing and publishing a channel's schedule. Each channel has one editable draft (version 0) and immutable
 * published versions 1..n; publishing copies the draft into a new version (rollback = restore + publish again).
 * Nothing already on air is ever moved or deleted.
 */
@Service
public class ScheduleServiceV1_0 {
    private final Data data;
    private final ScheduleRepository repository;
    private final MediaSettingsServiceV1_0 settings;
    private final Audit audit;

    public ScheduleServiceV1_0(Data data, ScheduleRepository repository, MediaSettingsServiceV1_0 settings, Audit audit) {
        this.data = data;
        this.repository = repository;
        this.settings = settings;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    public View view(Tenant t, UUID id, Instant from, int hours) {
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

    private static List<Entry> within(List<Entry> es, Instant f, Instant to) {
        return es.stream().filter(e -> e.end().isAfter(f) && e.start().isBefore(to)).toList();
    }

    /** Comparable fingerprint of a version's entries (used to show "unpublished changes"). */
    private static List<String> signature(List<Entry> es) {
        return es.stream().map(e -> e.kind() + e.assetId() + e.assetOffsetMs() + e.start() + e.end() + e.ownership()).toList();
    }

    // ------------------------------------------------------------------ draft edits

    /** Fills the draft after its last future item (or replaces the future) with enabled, ready programmes and breaks. */
    @Transactional
    public EditResult autofill(Tenant t, UUID id, AutofillRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Channel ch = data.channel(t.orgId(), id);
        Version draft = bump(t, id, r.expectedRevision());
        Instant now = Instant.now();
        if (r.replace()) repository.deleteStartingFrom(t.orgId(), draft.id(), Times.snapUp(now), true);
        List<Entry> existing = data.entries(t.orgId(), draft.id());
        Instant from = existing.stream().map(Entry::end).filter(e -> e.isAfter(now)).max(Comparator.naturalOrder()).orElse(Times.snapUp(now.plusSeconds(3)));
        Map<Long, Long> adPerHour = adSecondsPerHour(existing);
        Map<UUID, Asset> assets = data.assets(t.orgId());
        List<Asset> pool = r.assetIds() == null || r.assetIds().isEmpty()
                ? assets.values().stream().sorted(Comparator.comparing(Asset::createdAt)).toList()
                : r.assetIds().stream().map(assets::get).filter(Objects::nonNull).toList();
        int hours = Math.max(1, Math.min(r.hours() == null ? 24 : r.hours(), 72));
        Set<String> enabled = new HashSet<>(settings.view(t.orgId()).enabledTypes());
        Planner.Plan plan = Planner.autofill(ch, pool.stream().filter(a -> enabled.contains(a.mediaType())).toList(), data.policies(t.orgId()), from, hours, adPerHour);
        repository.insertEntries(t.orgId(), draft.id(), plan.items());
        audit.log(t, "schedule.autofill", "channel", id, hours + "h, " + plan.items().size() + " items");
        return new EditResult(draft.revision() + 1, plan.notices());
    }

    /** Adds one ad break or one programme (by default with its mid-rolls) at a time or after the last item. */
    @Transactional
    public EditResult add(Tenant t, UUID id, AddRequest r) {
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
            if (secs <= 0) throw ApiException.bad("Break length must be at least " + Times.SEG_S + " seconds");
            UUID inside = existing.stream().filter(e -> !e.isBreak() && e.end().equals(start)).map(Entry::assetId).findFirst().orElse(null);
            items = List.of(new Planner.Item("AD_BREAK", inside, 0, start, start.plusSeconds(secs), "PROVIDER"));
        } else {
            Asset a = data.asset(t.orgId(), r.assetId());
            settings.require(t.orgId(), a.mediaType());
            if (!a.ready()) throw ApiException.bad("\"" + a.title() + "\" isn't ready yet, so it can't be scheduled.");
            if (a.kind().equals("AD") || a.kind().equals("SLATE"))
                throw ApiException.bad("Ads are placed by the ad server inside ad breaks, not scheduled as programmes.");
            if (Boolean.FALSE.equals(r.withBreaks())) {
                items = List.of(new Planner.Item("PROGRAM", a.id(), 0, start, start.plusMillis(a.durationMs()), null));
            } else {
                // A one-programme auto-fill; keep only that programme and its mid-rolls.
                items = new ArrayList<>();
                for (Planner.Item it : Planner.autofill(ch, List.of(a), withPolicy(t, a), start, 1, adSecondsPerHour(existing)).items()) {
                    if (it.assetId() == null) break;      // stop at the between-programme break
                    items.add(it);
                    if (!it.kind().equals("AD_BREAK") && it.offsetMs() + (it.end().toEpochMilli() - it.start().toEpochMilli()) >= a.durationMs()) break;
                }
                if (items.isEmpty()) items = List.of(new Planner.Item("PROGRAM", a.id(), 0, start, start.plusMillis(a.durationMs()), null));
            }
        }
        repository.insertEntries(t.orgId(), draft.id(), items);
        audit.log(t, "schedule.add", "channel", id, r.kind() + " at " + start);
        return new EditResult(draft.revision() + 1, List.of("Added at " + Planner.fmt(start, ch.timezone()) + ". Check the issues list before publishing."));
    }

    private static Map<Long, Long> adSecondsPerHour(List<Entry> existing) {
        Map<Long, Long> adPerHour = new HashMap<>();
        existing.stream().filter(Entry::isBreak).forEach(e -> adPerHour.merge(Planner.hourOf(e.start()), e.durationMs(), Long::sum));
        return adPerHour;
    }

    /** Rights are reported by validation, not silently enforced by the manual "add" action. */
    private Map<UUID, Rights.Policy> withPolicy(Tenant t, Asset a) {
        Rights.Policy p = data.policy(t.orgId(), a.id());
        return Map.of(a.id(), new Rights.Policy(null, p == null ? null : p.assetId(), "WW", "", null, null, p == null || p.adsAllowed(), ""));
    }

    /** Removes an item; with {@code wholeProgramme}, also the other parts of that placement and its mid-roll breaks. */
    @Transactional
    public EditResult remove(Tenant t, UUID id, UUID entryId, int expectedRevision, boolean wholeProgramme) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, expectedRevision);
        List<Entry> es = data.entries(t.orgId(), draft.id());
        int idx = -1;
        for (int i = 0; i < es.size(); i++) if (es.get(i).id().equals(entryId)) idx = i;
        if (idx < 0) throw ApiException.notFound("Schedule item");
        Set<UUID> doomed = new HashSet<>(List.of(entryId));
        Entry target = es.get(idx);
        if (wholeProgramme && target.assetId() != null) {
            for (int i = idx - 1; i >= 0 && sameRun(es.get(i), es.get(i + 1), target.assetId()); i--) doomed.add(es.get(i).id());
            for (int i = idx + 1; i < es.size() && sameRun(es.get(i - 1), es.get(i), target.assetId()); i++) doomed.add(es.get(i).id());
        }
        repository.deleteEntries(t.orgId(), draft.id(), doomed);
        audit.log(t, "schedule.remove", "channel", id, doomed.size() + " item(s)");
        return new EditResult(draft.revision() + 1, List.of("Removed " + doomed.size() + " item(s). Use 'Close gaps' to pull later items forward."));
    }

    /** Adjacent entries belong to the same placement of {@code asset} (a new placement starts at offset 0). */
    private static boolean sameRun(Entry a, Entry b, UUID asset) {
        return a.end().equals(b.start()) && asset.equals(a.assetId()) && asset.equals(b.assetId())
                && !(b.kind().equals("PROGRAM") && b.assetOffsetMs() == 0);
    }

    /** Pulls future items earlier so there are no gaps; never moves what is already on air. */
    @Transactional
    public EditResult closeGaps(Tenant t, UUID id, RevisionRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, r.expectedRevision());
        Instant now = Instant.now();
        Instant cursor = null;
        int moved = 0;
        for (Entry e : data.entries(t.orgId(), draft.id())) {
            if (!e.start().isAfter(now)) { cursor = e.end(); continue; }
            Instant newStart = cursor == null || cursor.isBefore(now) ? Times.snapUp(now.plusSeconds(3)) : cursor;
            if (newStart.isBefore(e.start())) {
                repository.moveEntry(t.orgId(), e.id(), newStart, newStart.plusMillis(e.durationMs()));
                moved++;
                cursor = newStart.plusMillis(e.durationMs());
            } else cursor = e.end().isAfter(cursor == null ? e.end() : cursor) ? e.end() : cursor;
        }
        audit.log(t, "schedule.close_gaps", "channel", id, moved + " moved");
        return new EditResult(draft.revision() + 1, List.of(moved == 0 ? "No gaps to close." : "Moved " + moved + " item(s) earlier."));
    }

    @Transactional
    public EditResult clear(Tenant t, UUID id, RevisionRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, r.expectedRevision());
        int n = repository.deleteStartingFrom(t.orgId(), draft.id(), Instant.now(), false);
        audit.log(t, "schedule.clear", "channel", id, n + " future items");
        return new EditResult(draft.revision() + 1, List.of("Cleared " + n + " upcoming item(s). What's on air now is untouched."));
    }

    /** Replaces the draft with a published version's entries; publishing it again is the rollback. */
    @Transactional
    public EditResult restore(Tenant t, UUID id, RestoreRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        Version draft = bump(t, id, r.expectedRevision());
        Version v = data.versions(t.orgId(), id).stream().filter(x -> x.number() == r.versionNumber()).findFirst()
                .orElseThrow(() -> ApiException.notFound("Version"));
        repository.deleteAll(t.orgId(), draft.id());
        repository.copyEntries(t.orgId(), v.id(), draft.id());
        audit.log(t, "schedule.restore", "channel", id, "from v" + v.number());
        return new EditResult(draft.revision() + 1, List.of("Draft now matches version " + v.number() + ". Publish it to make it live again (rollback = new version)."));
    }

    // ------------------------------------------------------------------ publish

    /** Copies the draft into a new immutable version once it has no validation errors. */
    @Transactional
    public PublishResult publish(Tenant t, UUID id, PublishRequest r) {
        t.require(Tenant.Perm.SCHEDULE_PUBLISH);
        repository.lockChannel(t.orgId(), id);
        Channel ch = data.channel(t.orgId(), id);
        Version draft = data.draft(t.orgId(), id);
        if (draft.revision() != r.expectedRevision())
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE", "Someone changed this schedule since you loaded it. Reload to see their changes, then publish again.");
        List<Planner.Issue> issues = Planner.validate(ch, data.entries(t.orgId(), draft.id()), data.assets(t.orgId()), data.policies(t.orgId()), Instant.now());
        List<Planner.Issue> errors = issues.stream().filter(i -> i.severity().equals("ERROR")).toList();
        if (!errors.isEmpty())
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SCHEDULE_INVALID", "Fix " + errors.size() + " problem(s) before publishing.", errors);
        int next = repository.nextVersionNumber(id);
        UUID vid = repository.insertPublishedVersion(t.orgId(), id, next, r.note() == null ? "" : r.note(), t.userId());
        repository.copyEntries(t.orgId(), draft.id(), vid);
        repository.makeCurrent(t.orgId(), id, vid);
        audit.log(t, "schedule.publish", "channel", id, "version " + next + (r.note() == null || r.note().isBlank() ? "" : ": " + r.note()));
        return new PublishResult(next, issues);
    }

    /** Checks the channel and bumps the draft revision, or fails with 412 when the editor is stale. */
    private Version bump(Tenant t, UUID channelId, int expected) {
        data.channel(t.orgId(), channelId);
        Version d = data.draft(t.orgId(), channelId);
        if (!repository.bumpRevision(t.orgId(), d.id(), expected))
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE",
                    "Someone changed this schedule since you loaded it. Your view has been refreshed; please try again.");
        return d;
    }
}
