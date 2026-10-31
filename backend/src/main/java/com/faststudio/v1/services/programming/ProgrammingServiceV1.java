package com.faststudio.v1.services.programming;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;
import com.faststudio.v1.services.rights.Rights;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class ProgrammingServiceV1 {
    private final Data data;
    private final MediaSettingsServiceV1 settings;
    public ProgrammingServiceV1(Data data, MediaSettingsServiceV1 settings) { this.data = data; this.settings = settings; }
    public record Rule(UUID id, UUID orgId, UUID channelId, String series, LocalDate startDate, LocalDate endDate,
                       LocalTime premiereTime, LocalTime repeatTime, String weekdays, int startSeason, int startEpisode,
                       boolean loop, boolean enabled) {}
    public record Occurrence(LocalDate airDate, UUID assetId) {}
    public record Placement(UUID ruleId, LocalDate date, UUID assetId, String title, int season, int episode,
                            boolean encore, Instant start, Instant end, List<Planner.Item> items) {}
    public record Preview(int revision, List<Placement> placements, List<String> notices) {}
    public List<Rule> rules(UUID org, UUID channel) {
        return data.db.sql("select id,org_id,channel_id,series,start_date,end_date,premiere_time,repeat_time,weekdays,start_season,start_episode,loop,enabled from programming_rule where org_id=:o and channel_id=:c order by created_at,id")
                .param("o", org).param("c", channel).query(Rule.class).list();
    }
    public Preview preview(UUID org, UUID channel, List<Rule> rules, LocalDate through) {
        Data.Channel ch = data.channel(org, channel);
        Data.Version draft = data.draft(org, channel);
        List<Data.Entry> existing = data.entries(org, draft.id());
        List<Placement> out = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        Map<UUID, Rights.Policy> policies = data.policies(org);
        List<Data.Asset> assets = new ArrayList<>(data.assets(org).values());
        Map<Long,Long> ads = new HashMap<>();
        existing.stream().filter(Data.Entry::isBreak).forEach(e -> ads.merge(Planner.hourOf(e.start()), e.durationMs(), Long::sum));
        Set<String> enabled = new HashSet<>(settings.view(org).enabledTypes());
        for (Rule r : rules) {
            if (!r.enabled()) continue;
            List<Data.Asset> episodes = assets.stream().filter(a -> "EPISODE".equals(a.kind()) && !"ARCHIVED".equals(a.status()) && a.series() != null && a.series().trim().equalsIgnoreCase(r.series().trim())
                    && a.season() != null && a.episode() != null && a.season() > 0 && a.episode() > 0)
                    .sorted(Comparator.comparing(Data.Asset::season).thenComparing(Data.Asset::episode).thenComparing(Data.Asset::id)).toList();
            Map<LocalDate,UUID> done = new HashMap<>();
            if (r.id() != null) data.db.sql("select air_date,asset_id from programming_occurrence where org_id=:o and rule_id=:r order by air_date")
                    .param("o", org).param("r", r.id()).query(Occurrence.class).list().forEach(x -> done.put(x.airDate(), x.assetId()));
            int season = r.startSeason(), episode = r.startEpisode();
            LocalDate today = LocalDate.now(ZoneId.of(ch.timezone()));
            for (LocalDate day = r.startDate(); !day.isAfter(r.endDate()) && !day.isAfter(through); day = day.plusDays(1)) {
                if (!MediaSettingsServiceV1.days(r.weekdays()).contains(day.getDayOfWeek().getValue())) continue;
                if (done.containsKey(day)) {
                    UUID selected = done.get(day);
                    Data.Asset prior = assets.stream().filter(a -> a.id().equals(selected)).findFirst().orElse(null);
                    if (prior != null && prior.season() != null && prior.episode() != null) { season = prior.season(); episode = prior.episode() + 1; }
                    continue;
                }
                if (day.isBefore(today)) continue;
                final int wantSeason = season, wantEpisode = episode;
                List<Data.Asset> found = episodes.stream().filter(a -> a.season() == wantSeason && a.episode() == wantEpisode).toList();
                if (found.isEmpty() && episode > 1 && episodes.stream().noneMatch(a -> a.season() == wantSeason && a.episode() > wantEpisode)) {
                    found = episodes.stream().filter(a -> a.season() == wantSeason + 1 && a.episode() == 1).toList();
                    if (found.isEmpty() && r.loop() && episodes.stream().noneMatch(a -> a.season() > wantSeason))
                        found = episodes.stream().filter(a -> a.season() == r.startSeason() && a.episode() == r.startEpisode()).toList();
                }
                if (found.size() != 1) { notices.add(r.series() + " on " + day + ": " + (found.isEmpty() ? "waiting for the next episode (S" + season + " E" + episode + ")" : "duplicate episode numbers; fix the catalogue") + ". Later occurrences are held."); break; }
                Data.Asset asset = found.getFirst();
                if (!asset.ready() || !enabled.contains(asset.mediaType())) { notices.add(r.series() + " on " + day + ": next episode is not ready or its media type is disabled. Later occurrences are held."); break; }
                List<Placement> dayPlans = new ArrayList<>();
                Map<Long,Long> dayAds = new HashMap<>(ads);
                boolean failed = false;
                List<LocalTime> times = new ArrayList<>(List.of(r.premiereTime()));
                if (r.repeatTime() != null) times.add(r.repeatTime());
                for (int n = 0; n < times.size(); n++) {
                    Instant at;
                    try { at = wallClock(day, times.get(n), ch.timezone()); }
                    catch (ApiException e) { notices.add(r.series() + " on " + day + ": " + e.getMessage()); failed = true; break; }
                    if (!at.isAfter(Instant.now())) { notices.add(r.series() + " on " + day + ": premiere/repeat time has passed. Choose a future start date."); failed = true; break; }
                    List<Planner.Item> items = programme(ch, asset, policies, at, dayAds);
                    Instant end = items.isEmpty() ? at : items.getLast().end();
                    if (items.isEmpty() || ch.territoryList().stream().noneMatch(c -> Rights.evaluate(policies.get(asset.id()), ch.territoryList(), c, at, end).allowed())) {
                        notices.add(asset.title() + " on " + day + ": no licence covers this occurrence. Later occurrences are held."); failed = true; break;
                    }
                    boolean overlap = existing.stream().anyMatch(e -> e.start().isBefore(end) && e.end().isAfter(at))
                            || out.stream().anyMatch(p -> p.start().isBefore(end) && p.end().isAfter(at))
                            || dayPlans.stream().anyMatch(p -> p.start().isBefore(end) && p.end().isAfter(at));
                    if (overlap) { notices.add(r.series() + " on " + day + ": conflicts with an existing placement. Nothing is moved or replaced."); failed = true; break; }
                    dayPlans.add(new Placement(r.id(), day, asset.id(), asset.title(), asset.season(), asset.episode(), n > 0, at, end, items));
                    items.stream().filter(i -> i.kind().equals("AD_BREAK")).forEach(i -> dayAds.merge(Planner.hourOf(i.start()), i.end().toEpochMilli()-i.start().toEpochMilli(), Long::sum));
                }
                if (failed) break;
                out.addAll(dayPlans); ads.clear(); ads.putAll(dayAds);
                season = asset.season(); episode = asset.episode() + 1;
            }
        }
        return new Preview(draft.revision(), out, notices);
    }
    public static Instant wallClock(LocalDate day, LocalTime time, String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        LocalDateTime local = day.atTime(time);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw ApiException.bad("The local time is ambiguous or does not exist due to daylight saving. Choose another time.");
        return local.toInstant(offsets.getFirst());
    }
    static List<Planner.Item> programme(Data.Channel ch, Data.Asset asset, Map<UUID,Rights.Policy> policies, Instant at, Map<Long,Long> ads) {
        List<Planner.Item> first = new ArrayList<>();
        for (Planner.Item i : Planner.autofill(ch, List.of(asset), policies, at, 1, ads).items()) {
            if (i.assetId() == null) break;
            first.add(i);
            if (i.kind().equals("PROGRAM") && i.offsetMs() + i.end().toEpochMilli() - i.start().toEpochMilli() >= asset.durationMs()) break;
        }
        return first;
    }
}
