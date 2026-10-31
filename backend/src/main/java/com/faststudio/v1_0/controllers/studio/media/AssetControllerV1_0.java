package com.faststudio.v1_0.controllers.studio.media;

import com.faststudio.v1_0.core.common.Data.Asset;

import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.common.Times;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.services.media.MediaServiceV1_0;
import com.faststudio.v1_0.services.media.MediaSettingsServiceV1_0;
import com.faststudio.v1_0.services.media.MediaSigner;
import com.faststudio.v1_0.services.rights.Rights;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/orgs/{org}")
public class AssetControllerV1_0 {
    static final Set<String> KINDS = Set.of("MOVIE", "EPISODE", "SHORT", "AD");
    static final Set<String> UPLOAD_EXT = Set.of("mp4", "mov", "mkv", "webm", "m4v", "avi", "ts");
    static final List<String> SAMPLE_LOOKS = List.of(
            "gradients=size=640x360:rate=25:speed=0.012:c0=0x0f172a:c1=0x7c3aed:c2=0xf97316",
            "gradients=size=640x360:rate=25:speed=0.02:c0=0x042f2e:c1=0x14b8a6:c2=0xfde68a",
            "life=size=640x360:rate=25:mold=10:ratio=0.12:death_color=0x1e1b4b:life_color=0x22d3ee",
            "cellauto=size=640x360:rate=25:rule=110",
            "testsrc2=size=640x360:rate=25");

    private final Data data;
    private final MediaServiceV1_0 media;
    private final Audit audit;
    private final MediaSigner signer;
    private final MediaSettingsServiceV1_0 settings;

    public AssetControllerV1_0(Data data, MediaServiceV1_0 media, Audit audit, MediaSigner signer, MediaSettingsServiceV1_0 settings) {
        this.data = data;
        this.media = media;
        this.audit = audit;
        this.signer = signer;
        this.settings = settings;
    }

    public record AssetView(Asset asset, String thumbUrl, String previewUrl, Rights.Policy rights, String rightsStatus,
                            int upcomingAirings, List<String> channels) {}

    record Usage(UUID assetId, String channel) {}

    @GetMapping("/assets")
    List<AssetView> list(Tenant t, @RequestParam(required = false) String q, @RequestParam(required = false) String kind,
                         @RequestParam(defaultValue = "false") boolean archived) {
        Map<UUID, Rights.Policy> policies = data.policies(t.orgId());
        Map<UUID, List<String>> usage = usage(t.orgId());
        return data.assets(t.orgId()).values().stream()
                .filter(a -> archived == a.status().equals("ARCHIVED"))
                .filter(a -> kind == null || kind.isBlank() || a.kind().equals(kind))
                .filter(a -> q == null || q.isBlank() || (a.title() + " " + a.genre() + " " + Objects.toString(a.series(), "")).toLowerCase().contains(q.toLowerCase()))
                .sorted(Comparator.comparing(Asset::createdAt).reversed())
                .map(a -> view(a, policies.get(a.id()), usage.getOrDefault(a.id(), List.of())))
                .toList();
    }

    @GetMapping("/assets/{id}")
    AssetView get(Tenant t, @PathVariable UUID id) {
        Asset a = data.asset(t.orgId(), id);
        return view(a, data.policy(t.orgId(), id), usage(t.orgId()).getOrDefault(id, List.of()));
    }

    /** Upcoming published airings per asset, used for dependency checks and "where is this used". */
    private Map<UUID, List<String>> usage(UUID org) {
        Map<UUID, List<String>> out = new HashMap<>();
        data.db.sql("""
                select distinct e.asset_id, c.name as channel from schedule_entry e
                join channel c on c.org_id = e.org_id and c.published_version_id = e.version_id
                where e.org_id = :o and e.asset_id is not null and e.end_at > now() and e.kind = 'PROGRAM'""")
                .param("o", org).query(Usage.class).list()
                .forEach(u -> out.computeIfAbsent(u.assetId(), k -> new ArrayList<>()).add(u.channel()));
        return out;
    }

    AssetView view(Asset a, Rights.Policy p, List<String> channels) {
        String status;
        Instant now = Instant.now();
        if (p == null) status = "MISSING";
        else if (p.allowedCountries() == null || p.allowedCountries().isBlank()) status = "INCOMPLETE";
        else if (p.validUntil() != null && !p.validUntil().toInstant().isAfter(now)) status = "EXPIRED";
        else if (p.validUntil() != null && p.validUntil().toInstant().isBefore(now.plusSeconds(14 * 86400))) status = "EXPIRING";
        else status = "OK";
        String k = a.mediaKey();
        return new AssetView(a, k == null ? null : signer.url(k, "thumb.jpg") + "?v=" + a.updatedAt().toEpochSecond(),
                k == null ? null : signer.url(k, media.entryPlaylist(k)), p, a.kind().equals("AD") ? "N/A" : status, channels.size(), channels);
    }

    public record CreateRequest(@NotBlank @Size(max = 120) String title, @Size(max = 2000) String description, @NotBlank String kind,
                                String genre, String language, String series, Integer season, Integer episode, String color,
                                Integer sampleSeconds, String sampleTagline, String mediaType) {}

    /**
     * Creates a video record. With sampleSeconds set, ffmpeg generates a fictional demo video (no upload needed);
     * otherwise the record waits in DRAFT for an upload.
     */
    @PostMapping("/assets")
    @Transactional
    AssetView create(Tenant t, @Valid @RequestBody CreateRequest r) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        if (!KINDS.contains(r.kind())) throw ApiException.bad("Type must be one of " + KINDS);
        String type = r.mediaType() == null ? r.kind() : r.mediaType();
        if (!settings.require(t.orgId(), type).kind().equals(r.kind())) throw ApiException.bad("Media type does not match its scheduling behavior.");
        episodeMetadata(t.orgId(), r.kind(), r.series(), r.season(), r.episode(), null);
        String generator = null;
        long dur = 0;
        if (r.sampleSeconds() != null) {
            if (!media.ffmpegAvailable()) throw ApiException.conflict("NO_FFMPEG", "Sample generation needs ffmpeg on the server.");
            int secs = Math.max(15, Math.min(r.sampleSeconds(), 1800));
            dur = Times.snapMs(secs * 1000L);
            generator = r.kind().equals("AD") ? "ad:" + color(r.color()).replace("#", "0x") + "|" + Objects.toString(r.sampleTagline(), "")
                    : SAMPLE_LOOKS.get(Math.floorMod(r.title().hashCode(), SAMPLE_LOOKS.size()));
        }
        UUID id = data.db.sql("""
                insert into asset(org_id, title, description, kind, genre, language, series, season, episode, color, duration_ms, generator, status, cue_points, media_type)
                values (:o,:title,:d,:k,:g,:l,:s,:se,:ep,:c,:dur,:gen,:st,:cues,:mt) returning id""")
                .param("o", t.orgId()).param("title", r.title().trim()).param("d", Objects.toString(r.description(), ""))
                .param("k", r.kind()).param("g", Objects.toString(r.genre(), "")).param("l", Objects.toString(r.language(), "en"))
                .param("s", r.series() == null ? null : r.series().trim()).param("se", r.season()).param("ep", r.episode()).param("c", color(r.color())).param("mt", type)
                .param("dur", dur).param("gen", generator).param("st", generator != null ? "PROCESSING" : "DRAFT")
                .param("cues", defaultCues(r.kind(), dur)).query(UUID.class).single();
        audit.log(t, "asset.create", "asset", id, r.title() + (generator != null ? " (generated sample)" : ""));
        if (generator != null) media.enqueue(id);
        return get(t, id);
    }

    /** One mid-roll cue point in the middle of longer programmes, snapped to a segment boundary. */
    public static String defaultCues(String kind, long dur) {
        if (kind.equals("AD") || dur < 120_000) return "";
        return String.valueOf(Times.snapMs(dur / 2));
    }

    static String color(String c) {
        return c != null && c.matches("#[0-9a-fA-F]{6}") ? c : "#6366f1";
    }

    @PostMapping(value = "/assets/{id}/upload", consumes = "multipart/form-data")
    AssetView upload(Tenant t, @PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        if (a.status().equals("PROCESSING")) throw ApiException.conflict("BUSY", "This video is already being processed.");
        String name = Objects.toString(file.getOriginalFilename(), "");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!UPLOAD_EXT.contains(ext)) throw ApiException.bad("Unsupported file type ." + ext + ". Use MP4, MOV, MKV, WEBM or M4V.");
        if (file.isEmpty()) throw ApiException.bad("The file is empty.");
        if (!media.ffmpegAvailable()) throw ApiException.conflict("NO_FFMPEG", "The server has no ffmpeg, so uploads can't be processed.");
        Path dir = media.uploadDir(t.orgId(), id);
        Files.createDirectories(dir);
        try (var s = Files.list(dir)) { for (Path p : s.toList()) Files.deleteIfExists(p); }
        file.transferTo(dir.resolve("source." + ext));    // server-chosen name: no path traversal from the client filename
        // an upload replaces a library video as the source (ad creatives keep their ad-server identity)
        data.db.sql("update asset set status='PROCESSING', status_detail='Uploaded; converting to 5-second HLS segments', generator=null, "
                        + "source_ref = case when source_ref like 'mux%' then null else source_ref end, updated_at=now() where org_id=:o and id=:id")
                .param("o", t.orgId()).param("id", id).update();
        audit.log(t, "asset.upload", "asset", id, name + " (" + file.getSize() / 1024 + " KB)");
        media.enqueue(id);
        return get(t, id);
    }

    @PostMapping("/assets/{id}/retry")
    AssetView retry(Tenant t, @PathVariable UUID id) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        if (!a.status().equals("FAILED")) throw ApiException.conflict("NOT_FAILED", "Only failed videos can be retried.");
        data.db.sql("update asset set status='PROCESSING', status_detail='Retrying', updated_at=now() where org_id=:o and id=:id")
                .param("o", t.orgId()).param("id", id).update();
        media.enqueue(id);
        return get(t, id);
    }

    public record UpdateRequest(@Size(max = 120) String title, @Size(max = 2000) String description, String genre, String language,
                                List<Long> cuePoints, String series, Integer season, Integer episode, String mediaType) {}

    @PatchMapping("/assets/{id}")
    @Transactional
    AssetView update(Tenant t, @PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        String type = r.mediaType() == null ? a.mediaType() : r.mediaType();
        String kind = r.mediaType() == null ? a.kind() : settings.require(t.orgId(), type).kind();
        if (a.kind().equals("AD") != kind.equals("AD"))
            throw ApiException.bad("Advertisements and programmes cannot be interchanged. Create a separate asset.");
        String series = r.series() == null ? a.series() : r.series().trim();
        Integer season = r.season() == null ? a.season() : r.season(), episode = r.episode() == null ? a.episode() : r.episode();
        if (!kind.equals(a.kind()) || r.series() != null || r.season() != null || r.episode() != null)
            episodeMetadata(t.orgId(), kind, series, season, episode, id);
        String cues = a.cuePoints();
        if (r.cuePoints() != null) {
            List<Long> snapped = r.cuePoints().stream().map(Times::snapMs).filter(c -> c > 0 && (a.durationMs() == 0 || c < a.durationMs()))
                    .distinct().sorted().toList();
            cues = String.join(",", snapped.stream().map(String::valueOf).toList());
        }
        if (r.title() != null && r.title().isBlank()) throw ApiException.bad("Title can't be empty");
        data.db.sql("update asset set title=:t, description=:d, genre=:g, language=:l, cue_points=:c, series=:s,season=:se,episode=:ep,media_type=:mt,kind=:kind, updated_at=now() where org_id=:o and id=:id")
                .param("t", r.title() == null ? a.title() : r.title().trim()).param("d", r.description() == null ? a.description() : r.description())
                .param("g", r.genre() == null ? a.genre() : r.genre()).param("l", r.language() == null ? a.language() : r.language())
                .param("c", cues).param("s", series).param("se", season).param("ep", episode).param("mt", type).param("kind",kind).param("o", t.orgId()).param("id", id).update();
        audit.log(t, "asset.update", "asset", id, r.cuePoints() != null ? "cue points: " + cues : "metadata");
        return get(t, id);
    }

    private void episodeMetadata(UUID org, String kind, String series, Integer season, Integer episode, UUID except) {
        if (!"EPISODE".equals(kind)) return;
        if (series == null || series.isBlank() || series.trim().length() > 120 || season == null || season < 1 || episode == null || episode < 1)
            throw ApiException.bad("Episodes need a series name, a positive season number and a positive episode number.");
        data.db.sql("select id from organization where id=:o for update").param("o", org).query(UUID.class).single();
        if (data.db.sql("select count(*) from asset where org_id=:o and kind='EPISODE' and status<>'ARCHIVED' and lower(btrim(series))=lower(:s) and season=:se and episode=:ep and (cast(:id as uuid) is null or id<>:id)")
                .param("o", org).param("s", series.trim()).param("se", season).param("ep", episode).param("id", except).query(Integer.class).single() > 0)
            throw ApiException.conflict("DUPLICATE_EPISODE", "This series already has that season and episode number.");
    }

    /**
     * Archive, never hard-delete: blocked while the video is in a published upcoming schedule, a draft, a channel fallback
     * or an active campaign, so nothing on air breaks silently.
     */
    @PostMapping("/assets/{id}/archive")
    AssetView archive(Tenant t, @PathVariable UUID id) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        List<String> blockers = new ArrayList<>(usage(t.orgId()).getOrDefault(id, List.of()).stream().map(c -> "airs on " + c).toList());
        blockers.addAll(data.db.sql("select c.name from schedule_entry e join schedule_version v on v.id = e.version_id join channel c on c.id = v.channel_id where e.org_id = :o and e.asset_id = :a and v.number = 0 and e.end_at > now()")
                .param("o", t.orgId()).param("a", id).query(String.class).list().stream().distinct().map(c -> "in the draft schedule of " + c).toList());
        blockers.addAll(data.db.sql("select name from channel where org_id = :o and fallback_asset_id = :a").param("o", t.orgId()).param("a", id)
                .query(String.class).list().stream().map(c -> "fallback video of " + c).toList());
        blockers.addAll(data.db.sql("select name from campaign where org_id = :o and creative_asset_id = :a and status = 'ACTIVE'").param("o", t.orgId()).param("a", id)
                .query(String.class).list().stream().map(c -> "creative of active campaign " + c).toList());
        if (!blockers.isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "IN_USE", "\"" + a.title() + "\" is still in use: " + String.join("; ", blockers) + ".", blockers);
        data.db.sql("update asset set status='ARCHIVED', updated_at=now() where org_id=:o and id=:id").param("o", t.orgId()).param("id", id).update();
        audit.log(t, "asset.archive", "asset", id, a.title());
        return get(t, id);
    }

    // ------------------------------------------------------------------ rights

    public record RightsRequest(List<String> allowedCountries, List<String> blockedCountries, OffsetDateTime validFrom,
                                OffsetDateTime validUntil, boolean adsAllowed, String notes) {}

    @PutMapping("/assets/{id}/rights")
    @Transactional
    AssetView putRights(Tenant t, @PathVariable UUID id, @RequestBody RightsRequest r) {
        t.require(Tenant.Perm.RIGHTS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        if (r.validFrom() != null && r.validUntil() != null && !r.validUntil().isAfter(r.validFrom()))
            throw ApiException.bad("The licence end must be after its start.");
        for (String c : Times.codes(Times.csv(r.allowedCountries())))
            if (!c.matches("[A-Z]{2}")) throw ApiException.bad("Use 2-letter country codes (or WW for worldwide): " + c);
        data.db.sql("""
                insert into rights_policy(org_id, asset_id, allowed_countries, blocked_countries, valid_from, valid_until, ads_allowed, notes)
                values (:o,:a,:al,:bl,:f,:u,:ads,:n)
                on conflict (org_id, asset_id) do update set allowed_countries=excluded.allowed_countries, blocked_countries=excluded.blocked_countries,
                  valid_from=excluded.valid_from, valid_until=excluded.valid_until, ads_allowed=excluded.ads_allowed, notes=excluded.notes, updated_at=now()""")
                .param("o", t.orgId()).param("a", id).param("al", Times.csv(r.allowedCountries())).param("bl", Times.csv(r.blockedCountries()))
                .param("f", r.validFrom()).param("u", r.validUntil()).param("ads", r.adsAllowed()).param("n", Objects.toString(r.notes(), ""))
                .update();
        audit.log(t, "rights.update", "asset", id, a.title() + ": allowed=" + Times.csv(r.allowedCountries()) + " blocked=" + Times.csv(r.blockedCountries())
                + " until=" + r.validUntil() + " ads=" + r.adsAllowed());
        return get(t, id);
    }

    public record CheckRequest(@NotNull UUID assetId, @NotBlank String country, UUID channelId, Instant at, Integer minutes) {}

    public record CheckResult(Rights.Decision decision, String asset, String channel, Instant from, Instant to, boolean simulated) {}

    /** "Can this play in X at time T?" — evaluates the stored rules; the country is a simulated input, not a geo-IP lookup. */
    @PostMapping("/rights/check")
    CheckResult check(Tenant t, @Valid @RequestBody CheckRequest r) {
        Asset a = data.asset(t.orgId(), r.assetId());
        Data.Channel ch = r.channelId() == null ? null : data.channel(t.orgId(), r.channelId());
        Instant from = r.at() == null ? Instant.now() : r.at();
        Instant to = from.plusMillis(r.minutes() == null ? Math.max(a.durationMs(), 1) : r.minutes() * 60_000L);
        Rights.Decision d = Rights.evaluate(data.policy(t.orgId(), a.id()), ch == null ? null : ch.territoryList(), r.country(), from, to);
        return new CheckResult(d, a.title(), ch == null ? null : ch.name(), from, to, true);
    }
}
