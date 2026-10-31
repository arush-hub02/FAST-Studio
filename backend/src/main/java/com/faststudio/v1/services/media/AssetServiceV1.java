package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Data.Asset;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.AssetDto.*;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.services.rights.Rights;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * The media library: create (optionally a generated demo sample), upload, retry, edit metadata/cue points,
 * archive (never hard-delete), and per-asset rights. Processing itself runs in {@link MediaServiceV1}.
 */
@Service
public class AssetServiceV1 {
    static final Set<String> KINDS = Set.of("MOVIE", "EPISODE", "SHORT", "AD");
    static final Set<String> UPLOAD_EXT = Set.of("mp4", "mov", "mkv", "webm", "m4v", "avi", "ts");
    /** ffmpeg sources for generated demo videos; one is picked from the title so samples look different. */
    static final List<String> SAMPLE_LOOKS = List.of(
            "gradients=size=640x360:rate=25:speed=0.012:c0=0x0f172a:c1=0x7c3aed:c2=0xf97316",
            "gradients=size=640x360:rate=25:speed=0.02:c0=0x042f2e:c1=0x14b8a6:c2=0xfde68a",
            "life=size=640x360:rate=25:mold=10:ratio=0.12:death_color=0x1e1b4b:life_color=0x22d3ee",
            "cellauto=size=640x360:rate=25:rule=110",
            "testsrc2=size=640x360:rate=25");

    private final Data data;
    private final AssetRepository repository;
    private final MediaServiceV1 media;
    private final MediaSigner signer;
    private final MediaSettingsServiceV1 settings;
    private final Audit audit;

    public AssetServiceV1(Data data, AssetRepository repository, MediaServiceV1 media, MediaSigner signer,
                            MediaSettingsServiceV1 settings, Audit audit) {
        this.data = data;
        this.repository = repository;
        this.media = media;
        this.signer = signer;
        this.settings = settings;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    public List<AssetView> list(Tenant t, String q, String kind, boolean archived) {
        Map<UUID, Rights.Policy> policies = data.policies(t.orgId());
        Map<UUID, List<String>> usage = repository.upcomingAirings(t.orgId());
        return data.assets(t.orgId()).values().stream()
                .filter(a -> archived == a.status().equals("ARCHIVED"))
                .filter(a -> kind == null || kind.isBlank() || a.kind().equals(kind))
                .filter(a -> q == null || q.isBlank() || (a.title() + " " + a.genre() + " " + Objects.toString(a.series(), "")).toLowerCase().contains(q.toLowerCase()))
                .sorted(Comparator.comparing(Asset::createdAt).reversed())
                .map(a -> view(a, policies.get(a.id()), usage.getOrDefault(a.id(), List.of())))
                .toList();
    }

    public AssetView get(Tenant t, UUID id) {
        Asset a = data.asset(t.orgId(), id);
        return view(a, data.policy(t.orgId(), id), repository.upcomingAirings(t.orgId()).getOrDefault(id, List.of()));
    }

    private AssetView view(Asset a, Rights.Policy p, List<String> channels) {
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

    // ------------------------------------------------------------------ writing

    @Transactional
    public AssetView create(Tenant t, CreateRequest r) {
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
        UUID id = repository.insert(t.orgId(), new AssetRepository.NewAsset(r.title().trim(), Objects.toString(r.description(), ""), r.kind(),
                Objects.toString(r.genre(), ""), Objects.toString(r.language(), "en"), r.series() == null ? null : r.series().trim(),
                r.season(), r.episode(), color(r.color()), dur, generator, generator != null ? "PROCESSING" : "DRAFT",
                defaultCues(r.kind(), dur), type));
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

    /** Stores the file under a server-chosen name (no path traversal from the client filename) and queues processing. */
    public AssetView upload(Tenant t, UUID id, MultipartFile file) throws IOException {
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
        file.transferTo(dir.resolve("source." + ext));
        repository.markUploaded(t.orgId(), id);
        audit.log(t, "asset.upload", "asset", id, name + " (" + file.getSize() / 1024 + " KB)");
        media.enqueue(id);
        return get(t, id);
    }

    public AssetView retry(Tenant t, UUID id) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        if (!a.status().equals("FAILED")) throw ApiException.conflict("NOT_FAILED", "Only failed videos can be retried.");
        repository.markProcessing(t.orgId(), id, "Retrying");
        media.enqueue(id);
        return get(t, id);
    }

    @Transactional
    public AssetView update(Tenant t, UUID id, UpdateRequest r) {
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
        repository.updateMetadata(t.orgId(), id, new AssetRepository.Metadata(
                r.title() == null ? a.title() : r.title().trim(), r.description() == null ? a.description() : r.description(),
                r.genre() == null ? a.genre() : r.genre(), r.language() == null ? a.language() : r.language(),
                cues, series, season, episode, type, kind));
        audit.log(t, "asset.update", "asset", id, r.cuePoints() != null ? "cue points: " + cues : "metadata");
        return get(t, id);
    }

    /** Episodes need series/season/episode, unique within the project (checked under a project lock). */
    private void episodeMetadata(UUID org, String kind, String series, Integer season, Integer episode, UUID except) {
        if (!"EPISODE".equals(kind)) return;
        if (series == null || series.isBlank() || series.trim().length() > 120 || season == null || season < 1 || episode == null || episode < 1)
            throw ApiException.bad("Episodes need a series name, a positive season number and a positive episode number.");
        repository.lockProject(org);
        if (repository.episodeTaken(org, series.trim(), season, episode, except))
            throw ApiException.conflict("DUPLICATE_EPISODE", "This series already has that season and episode number.");
    }

    /**
     * Archive, never hard-delete: blocked while the video is in a published upcoming schedule, a draft, a channel fallback
     * or an active campaign, so nothing on air breaks silently.
     */
    public AssetView archive(Tenant t, UUID id) {
        t.require(Tenant.Perm.ASSETS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        List<String> blockers = new ArrayList<>(repository.upcomingAirings(t.orgId()).getOrDefault(id, List.of()).stream().map(c -> "airs on " + c).toList());
        blockers.addAll(repository.draftChannelsUsing(t.orgId(), id).stream().map(c -> "in the draft schedule of " + c).toList());
        blockers.addAll(repository.channelsWithFallback(t.orgId(), id).stream().map(c -> "fallback video of " + c).toList());
        blockers.addAll(repository.activeCampaignsUsing(t.orgId(), id).stream().map(c -> "creative of active campaign " + c).toList());
        if (!blockers.isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "IN_USE", "\"" + a.title() + "\" is still in use: " + String.join("; ", blockers) + ".", blockers);
        repository.archive(t.orgId(), id);
        audit.log(t, "asset.archive", "asset", id, a.title());
        return get(t, id);
    }

    // ------------------------------------------------------------------ rights

    @Transactional
    public AssetView putRights(Tenant t, UUID id, RightsRequest r) {
        t.require(Tenant.Perm.RIGHTS_EDIT);
        Asset a = data.asset(t.orgId(), id);
        if (r.validFrom() != null && r.validUntil() != null && !r.validUntil().isAfter(r.validFrom()))
            throw ApiException.bad("The licence end must be after its start.");
        for (String c : Times.codes(Times.csv(r.allowedCountries())))
            if (!c.matches("[A-Z]{2}")) throw ApiException.bad("Use 2-letter country codes (or WW for worldwide): " + c);
        repository.upsertRights(t.orgId(), id, Times.csv(r.allowedCountries()), Times.csv(r.blockedCountries()),
                r.validFrom(), r.validUntil(), r.adsAllowed(), Objects.toString(r.notes(), ""));
        audit.log(t, "rights.update", "asset", id, a.title() + ": allowed=" + Times.csv(r.allowedCountries()) + " blocked=" + Times.csv(r.blockedCountries())
                + " until=" + r.validUntil() + " ads=" + r.adsAllowed());
        return get(t, id);
    }

    /** Evaluates the stored rules for a simulated country and time window. */
    public CheckResult check(Tenant t, CheckRequest r) {
        Asset a = data.asset(t.orgId(), r.assetId());
        Data.Channel ch = r.channelId() == null ? null : data.channel(t.orgId(), r.channelId());
        Instant from = r.at() == null ? Instant.now() : r.at();
        Instant to = from.plusMillis(r.minutes() == null ? Math.max(a.durationMs(), 1) : r.minutes() * 60_000L);
        Rights.Decision d = Rights.evaluate(data.policy(t.orgId(), a.id()), ch == null ? null : ch.territoryList(), r.country(), from, to);
        return new CheckResult(d, a.title(), ch == null ? null : ch.name(), from, to, true);
    }
}
