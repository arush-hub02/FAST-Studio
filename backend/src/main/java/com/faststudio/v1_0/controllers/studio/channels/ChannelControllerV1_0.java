package com.faststudio.v1_0.controllers.studio.channels;

import com.faststudio.v1_0.core.common.Data.Channel;

import com.faststudio.v1_0.controllers.studio.programming.ScheduleControllerV1_0;
import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.common.Times;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.services.media.MediaSettingsServiceV1_0;
import com.faststudio.v1_0.services.programming.Planner;
import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;


import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/orgs/{org}/channels")
public class ChannelControllerV1_0 {
    private final Data data;
    private final Audit audit;
    private final MediaSettingsServiceV1_0 settings;
    private final TenantConfigurationServiceV1_0 configuration;

    public ChannelControllerV1_0(Data data, Audit audit, MediaSettingsServiceV1_0 settings, TenantConfigurationServiceV1_0 configuration) {
        this.data = data;
        this.audit = audit;
        this.settings = settings;
        this.configuration = configuration;
    }

    public record ChannelView(Channel channel, ScheduleControllerV1_0.Programme now, ScheduleControllerV1_0.Programme next,
                              Instant scheduledUntil, Integer publishedVersion, List<String> readiness, String fallbackTitle) {}

    @GetMapping
    List<ChannelView> list(Tenant t) {
        return data.channels(t.orgId()).stream().map(c -> view(t, c)).toList();
    }

    @GetMapping("/{id}")
    ChannelView get(Tenant t, @PathVariable UUID id) {
        return view(t, data.channel(t.orgId(), id));
    }

    ChannelView view(Tenant t, Channel c) {
        Instant now = Instant.now();
        var guide = ScheduleControllerV1_0.guide(data, t.orgId(), c, now.minusSeconds(60), now.plusSeconds(12 * 3600));
        var cur = guide.stream().filter(ScheduleControllerV1_0.Programme::now).findFirst().orElse(null);
        var next = guide.stream().filter(p -> p.start().isAfter(now)).findFirst().orElse(null);
        if (cur == null && c.publishedVersionId() != null) {
            var activeBreak = data.entries(t.orgId(), c.publishedVersionId(), now.minusSeconds(600), now.plusSeconds(600)).stream()
                    .filter(e -> e.isBreak() && !e.start().isAfter(now) && e.end().isAfter(now))
                    .findFirst().orElse(null);
            if (activeBreak != null) {
                cur = new ScheduleControllerV1_0.Programme(activeBreak.id(), "Commercial Ad Break", "Programmatic SSAI commercial break",
                        "Commercial", "AD", "#f59e0b", null, activeBreak.start(), activeBreak.end(), 1, true, null,null,null,null);
            }
        }
        Integer version = c.publishedVersionId() == null ? null : data.db.sql("select number from schedule_version where id = :v")
                .param("v", c.publishedVersionId()).query(Integer.class).single();
        String fb = c.fallbackAssetId() == null ? null : data.asset(t.orgId(), c.fallbackAssetId()).title();
        return new ChannelView(c, cur, next, data.coverageEnd(t.orgId(), c.publishedVersionId()), version, readiness(t, c), fb);
    }

    /** Plain-language reasons the channel can't go live yet (empty = ready). */
    List<String> readiness(Tenant t, Channel c) {
        List<String> out = new ArrayList<>();
        if (c.territoryList().isEmpty()) out.add("Choose at least one country where the channel is offered");
        Instant end = data.coverageEnd(t.orgId(), c.publishedVersionId());
        if (c.publishedVersionId() == null) out.add("Publish a schedule");
        else if (end == null || !end.isAfter(Instant.now())) out.add("The published schedule has nothing from now on — auto-fill and publish");
        return out;
    }

    public record CreateRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description, String genre, String language,
                                String timezone, String color, List<String> territories, Integer adSecondsPerHour, Integer breakSeconds,
                                UUID fallbackAssetId, List<UUID> assetIds, Integer autofillHours) {}

    @PostMapping
    @Transactional
    ChannelView create(Tenant t, @Valid @RequestBody CreateRequest r) {
        t.require(Tenant.Perm.CHANNELS_EDIT);
        // The tenant's subscription plan is the only channel limit; it is shared by all projects of the tenant.
        configuration.reserveChannel(t.orgId());
        String slug = slugify(r.name());
        int clash = data.db.sql("select count(*) from channel where org_id = :o and slug like :s").param("o", t.orgId()).param("s", slug + "%").query(Integer.class).single();
        if (clash > 0) slug = slug + "-" + (clash + 1);
        String tz = r.timezone() == null || r.timezone().isBlank() ? "UTC" : r.timezone();
        try { ZoneId.of(tz); } catch (Exception e) { throw ApiException.bad("Unknown time zone " + tz); }
        if (r.fallbackAssetId() != null) data.asset(t.orgId(), r.fallbackAssetId());   // tenant check
        UUID id = data.db.sql("""
                insert into channel(org_id, slug, name, description, genre, language, timezone, color, territories, ad_seconds_per_hour, break_seconds, fallback_asset_id)
                values (:o,:slug,:n,:d,:g,:l,:tz,:c,:terr,:ads,:brk,:fb) returning id""")
                .param("o", t.orgId()).param("slug", slug).param("n", r.name().trim()).param("d", Objects.toString(r.description(), ""))
                .param("g", Objects.toString(r.genre(), "")).param("l", Objects.toString(r.language(), "en")).param("tz", tz)
                .param("c", r.color() != null && r.color().matches("#[0-9a-fA-F]{6}") ? r.color() : "#6366f1")
                .param("terr", Times.csv(r.territories()))
                .param("ads", clamp(r.adSecondsPerHour(), 0, 1200, 480)).param("brk", (int) (Times.snapMs(clamp(r.breakSeconds(), 15, 300, 90) * 1000L) / 1000))
                .param("fb", r.fallbackAssetId()).query(UUID.class).single();
        data.db.sql("insert into schedule_version(org_id, channel_id, number, status, created_by) values (:o,:c,0,'DRAFT',:u)")
                .param("o", t.orgId()).param("c", id).param("u", t.userId()).update();
        audit.log(t, "channel.create", "channel", id, r.name());
        if (r.assetIds() != null && !r.assetIds().isEmpty()) {
            Channel ch = data.channel(t.orgId(), id);
            var assets = data.assets(t.orgId());
            Set<String> enabled = new HashSet<>(settings.view(t.orgId()).enabledTypes());
            var pool = r.assetIds().stream().map(assets::get).filter(Objects::nonNull).filter(a -> enabled.contains(a.mediaType())).toList();
            var plan = Planner.autofill(ch, pool, data.policies(t.orgId()), Instant.now().plusSeconds(60), clamp(r.autofillHours(), 1, 72, 24), Map.of());
            UUID draft = data.draft(t.orgId(), id).id();
            for (var it : plan.items())
                data.db.sql("insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership) values (:o,:v,:k,:a,:off,:s,:e,:own)")
                        .param("o", t.orgId()).param("v", draft).param("k", it.kind()).param("a", it.assetId()).param("off", it.offsetMs())
                        .param("s", Times.utc(it.start())).param("e", Times.utc(it.end())).param("own", it.ownership()).update();
        }
        return get(t, id);
    }

    static int clamp(Integer v, int lo, int hi, int dflt) {
        return v == null ? dflt : Math.max(lo, Math.min(hi, v));
    }

    static String slugify(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "channel" : slug.substring(0, Math.min(slug.length(), 40));
    }

    public record UpdateRequest(String name, String description, String genre, String language, String timezone, String color,
                                List<String> territories, Integer adSecondsPerHour, Integer breakSeconds, UUID fallbackAssetId, Boolean clearFallback) {}

    @PatchMapping("/{id}")
    ChannelView update(Tenant t, @PathVariable UUID id, @RequestBody UpdateRequest r) {
        t.require(Tenant.Perm.CHANNELS_EDIT);
        Channel c = data.channel(t.orgId(), id);
        if (r.timezone() != null) try { ZoneId.of(r.timezone()); } catch (Exception e) { throw ApiException.bad("Unknown time zone"); }
        if (r.fallbackAssetId() != null) data.asset(t.orgId(), r.fallbackAssetId());
        if (r.name() != null && r.name().isBlank()) throw ApiException.bad("Name can't be empty");
        UUID fb = Boolean.TRUE.equals(r.clearFallback()) ? null : r.fallbackAssetId() != null ? r.fallbackAssetId() : c.fallbackAssetId();
        data.db.sql("""
                update channel set name=:n, description=:d, genre=:g, language=:l, timezone=:tz, color=:c, territories=:terr,
                  ad_seconds_per_hour=:ads, break_seconds=:brk, fallback_asset_id=:fb, updated_at=now() where org_id=:o and id=:id""")
                .param("n", r.name() == null ? c.name() : r.name().trim()).param("d", r.description() == null ? c.description() : r.description())
                .param("g", r.genre() == null ? c.genre() : r.genre()).param("l", r.language() == null ? c.language() : r.language())
                .param("tz", r.timezone() == null ? c.timezone() : r.timezone())
                .param("c", r.color() != null && r.color().matches("#[0-9a-fA-F]{6}") ? r.color() : c.color())
                .param("terr", r.territories() == null ? c.territories() : Times.csv(r.territories()))
                .param("ads", clamp(r.adSecondsPerHour(), 0, 1200, c.adSecondsPerHour()))
                .param("brk", (int) (Times.snapMs(clamp(r.breakSeconds(), 15, 300, c.breakSeconds()) * 1000L) / 1000))
                .param("fb", fb).param("o", t.orgId()).param("id", id).update();
        audit.log(t, "channel.update", "channel", id, c.name());
        return get(t, id);
    }

    @PostMapping("/{id}/go-live")
    ChannelView goLive(Tenant t, @PathVariable UUID id) {
        t.require(Tenant.Perm.SCHEDULE_PUBLISH);
        Channel c = data.channel(t.orgId(), id);
        List<String> blockers = readiness(t, c);
        if (!blockers.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "NOT_READY", "Not ready to go live: " + String.join("; ", blockers) + ".", blockers);
        setStatus(t, c, "LIVE");
        return get(t, id);
    }

    @PostMapping("/{id}/stop")
    ChannelView stop(Tenant t, @PathVariable UUID id) {
        t.require(Tenant.Perm.SCHEDULE_PUBLISH);
        Channel c = data.channel(t.orgId(), id);
        if (!c.status().equals("LIVE")) throw ApiException.conflict("NOT_LIVE", "The channel isn't live.");
        setStatus(t, c, "STOPPED");
        return get(t, id);
    }

    @PostMapping("/{id}/archive")
    ChannelView archive(Tenant t, @PathVariable UUID id) {
        t.require(Tenant.Perm.CHANNELS_EDIT);
        Channel c = data.channel(t.orgId(), id);
        if (c.status().equals("LIVE")) throw ApiException.conflict("LIVE", "Stop the channel before archiving it.");
        setStatus(t, c, "ARCHIVED");
        return view(t, data.channel(t.orgId(), id));
    }

    private void setStatus(Tenant t, Channel c, String status) {
        data.db.sql("update channel set status=:s, updated_at=now() where org_id=:o and id=:id").param("s", status).param("o", t.orgId()).param("id", c.id()).update();
        audit.log(t, "channel." + status.toLowerCase(), "channel", c.id(), c.name());
    }
}
