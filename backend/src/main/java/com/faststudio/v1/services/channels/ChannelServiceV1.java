package com.faststudio.v1.services.channels;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.channels.ChannelDto.*;
import com.faststudio.v1.dto.programming.ScheduleDto.Programme;
import com.faststudio.v1.repositories.channels.ChannelRepository;
import com.faststudio.v1.repositories.programming.ScheduleRepository;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;
import com.faststudio.v1.services.programming.EpgServiceV1;
import com.faststudio.v1.services.programming.Planner;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Channels of a project: create (within the tenant plan's channel allowance, optionally auto-filled), edit,
 * go live / stop / archive. A channel goes live only when it has territories and a published schedule covering now.
 */
@Service
public class ChannelServiceV1 {
    private static final String DEFAULT_COLOR = "#6366f1";

    private final Data data;
    private final ChannelRepository repository;
    private final ScheduleRepository schedules;
    private final EpgServiceV1 epg;
    private final MediaSettingsServiceV1 settings;
    private final TenantConfigurationServiceV1 configuration;
    private final Audit audit;

    public ChannelServiceV1(Data data, ChannelRepository repository, ScheduleRepository schedules, EpgServiceV1 epg,
                              MediaSettingsServiceV1 settings, TenantConfigurationServiceV1 configuration, Audit audit) {
        this.data = data;
        this.repository = repository;
        this.schedules = schedules;
        this.epg = epg;
        this.settings = settings;
        this.configuration = configuration;
        this.audit = audit;
    }

    public List<ChannelView> list(Tenant t) {
        return data.channels(t.orgId()).stream().map(c -> view(t, c)).toList();
    }

    public ChannelView get(Tenant t, UUID id) {
        return view(t, data.channel(t.orgId(), id));
    }

    private ChannelView view(Tenant t, Channel c) {
        Instant now = Instant.now();
        var guide = epg.guide(t.orgId(), c, now.minusSeconds(60), now.plusSeconds(12 * 3600));
        var cur = guide.stream().filter(Programme::now).findFirst().orElse(null);
        var next = guide.stream().filter(p -> p.start().isAfter(now)).findFirst().orElse(null);
        if (cur == null && c.publishedVersionId() != null) {
            // The guide skips breaks; show an ad break that is on air right now as the current item.
            var activeBreak = data.entries(t.orgId(), c.publishedVersionId(), now.minusSeconds(600), now.plusSeconds(600)).stream()
                    .filter(e -> e.isBreak() && !e.start().isAfter(now) && e.end().isAfter(now))
                    .findFirst().orElse(null);
            if (activeBreak != null)
                cur = new Programme(activeBreak.id(), "Commercial Ad Break", "Programmatic SSAI commercial break",
                        "Commercial", "AD", "#f59e0b", null, activeBreak.start(), activeBreak.end(), 1, true, null, null, null, null);
        }
        Integer version = c.publishedVersionId() == null ? null : schedules.versionNumber(t.orgId(), c.publishedVersionId());
        String fallback = c.fallbackAssetId() == null ? null : data.asset(t.orgId(), c.fallbackAssetId()).title();
        return new ChannelView(c, cur, next, data.coverageEnd(t.orgId(), c.publishedVersionId()), version, readiness(t, c), fallback);
    }

    /** Plain-language reasons the channel can't go live yet (empty = ready). */
    private List<String> readiness(Tenant t, Channel c) {
        List<String> out = new ArrayList<>();
        if (c.territoryList().isEmpty()) out.add("Choose at least one country where the channel is offered");
        Instant end = data.coverageEnd(t.orgId(), c.publishedVersionId());
        if (c.publishedVersionId() == null) out.add("Publish a schedule");
        else if (end == null || !end.isAfter(Instant.now())) out.add("The published schedule has nothing from now on — auto-fill and publish");
        return out;
    }

    @Transactional
    public ChannelView create(Tenant t, CreateRequest r) {
        t.require(Tenant.Perm.CHANNELS_EDIT);
        // The tenant's subscription plan is the only channel limit; it is shared by all projects of the tenant.
        configuration.reserveChannel(t.orgId());
        String slug = slugify(r.name());
        int clash = repository.slugsStartingWith(t.orgId(), slug);
        if (clash > 0) slug = slug + "-" + (clash + 1);
        String tz = r.timezone() == null || r.timezone().isBlank() ? "UTC" : r.timezone();
        try { ZoneId.of(tz); } catch (Exception e) { throw ApiException.bad("Unknown time zone " + tz); }
        if (r.fallbackAssetId() != null) data.asset(t.orgId(), r.fallbackAssetId());   // must belong to this project
        UUID id = repository.insert(t.orgId(), slug, new ChannelRepository.Fields(r.name().trim(), Objects.toString(r.description(), ""),
                Objects.toString(r.genre(), ""), Objects.toString(r.language(), "en"), tz,
                r.color() != null && r.color().matches("#[0-9a-fA-F]{6}") ? r.color() : DEFAULT_COLOR, Times.csv(r.territories()),
                clamp(r.adSecondsPerHour(), 0, 1200, 480), breakSeconds(clamp(r.breakSeconds(), 15, 300, 90)), r.fallbackAssetId()));
        schedules.createDraft(t.orgId(), id, t.userId());
        audit.log(t, "channel.create", "channel", id, r.name());
        if (r.assetIds() != null && !r.assetIds().isEmpty()) autofillNewChannel(t, id, r.assetIds(), clamp(r.autofillHours(), 1, 72, 24));
        return get(t, id);
    }

    /** Fills the new channel's draft from the chosen videos whose media type is enabled. */
    private void autofillNewChannel(Tenant t, UUID id, List<UUID> assetIds, int hours) {
        Channel ch = data.channel(t.orgId(), id);
        var assets = data.assets(t.orgId());
        Set<String> enabled = new HashSet<>(settings.view(t.orgId()).enabledTypes());
        var pool = assetIds.stream().map(assets::get).filter(Objects::nonNull).filter(a -> enabled.contains(a.mediaType())).toList();
        var plan = Planner.autofill(ch, pool, data.policies(t.orgId()), Instant.now().plusSeconds(60), hours, Map.of());
        schedules.insertEntries(t.orgId(), data.draft(t.orgId(), id).id(), plan.items());
    }

    private static int clamp(Integer v, int lo, int hi, int dflt) {
        return v == null ? dflt : Math.max(lo, Math.min(hi, v));
    }

    /** Break length snapped to whole segments. */
    private static int breakSeconds(int seconds) {
        return (int) (Times.snapMs(seconds * 1000L) / 1000);
    }

    private static String slugify(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "channel" : slug.substring(0, Math.min(slug.length(), 40));
    }

    public ChannelView update(Tenant t, UUID id, UpdateRequest r) {
        t.require(Tenant.Perm.CHANNELS_EDIT);
        Channel c = data.channel(t.orgId(), id);
        if (r.timezone() != null) try { ZoneId.of(r.timezone()); } catch (Exception e) { throw ApiException.bad("Unknown time zone"); }
        if (r.fallbackAssetId() != null) data.asset(t.orgId(), r.fallbackAssetId());
        if (r.name() != null && r.name().isBlank()) throw ApiException.bad("Name can't be empty");
        UUID fallback = Boolean.TRUE.equals(r.clearFallback()) ? null : r.fallbackAssetId() != null ? r.fallbackAssetId() : c.fallbackAssetId();
        repository.update(t.orgId(), id, new ChannelRepository.Fields(
                r.name() == null ? c.name() : r.name().trim(), r.description() == null ? c.description() : r.description(),
                r.genre() == null ? c.genre() : r.genre(), r.language() == null ? c.language() : r.language(),
                r.timezone() == null ? c.timezone() : r.timezone(),
                r.color() != null && r.color().matches("#[0-9a-fA-F]{6}") ? r.color() : c.color(),
                r.territories() == null ? c.territories() : Times.csv(r.territories()),
                clamp(r.adSecondsPerHour(), 0, 1200, c.adSecondsPerHour()), breakSeconds(clamp(r.breakSeconds(), 15, 300, c.breakSeconds())), fallback));
        audit.log(t, "channel.update", "channel", id, c.name());
        return get(t, id);
    }

    public ChannelView goLive(Tenant t, UUID id) {
        t.require(Tenant.Perm.SCHEDULE_PUBLISH);
        Channel c = data.channel(t.orgId(), id);
        List<String> blockers = readiness(t, c);
        if (!blockers.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "NOT_READY", "Not ready to go live: " + String.join("; ", blockers) + ".", blockers);
        setStatus(t, c, "LIVE");
        return get(t, id);
    }

    public ChannelView stop(Tenant t, UUID id) {
        t.require(Tenant.Perm.SCHEDULE_PUBLISH);
        Channel c = data.channel(t.orgId(), id);
        if (!c.status().equals("LIVE")) throw ApiException.conflict("NOT_LIVE", "The channel isn't live.");
        setStatus(t, c, "STOPPED");
        return get(t, id);
    }

    public ChannelView archive(Tenant t, UUID id) {
        t.require(Tenant.Perm.CHANNELS_EDIT);
        Channel c = data.channel(t.orgId(), id);
        if (c.status().equals("LIVE")) throw ApiException.conflict("LIVE", "Stop the channel before archiving it.");
        setStatus(t, c, "ARCHIVED");
        return get(t, id);
    }

    private void setStatus(Tenant t, Channel c, String status) {
        repository.setStatus(t.orgId(), c.id(), status);
        audit.log(t, "channel." + status.toLowerCase(), "channel", c.id(), c.name());
    }
}
