package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.repositories.media.AssetRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Asset metadata and rights rules shared by every way an asset is made or changed: library edits, library sync and
 * webhooks today, CSV import and batch upload later. One place, so an import can never accept what an edit refuses.
 */
@Component
public class AssetRules {
    public static final Set<String> KINDS = Set.of("MOVIE", "EPISODE", "SHORT", "AD");
    public static final int TITLE_MAX = 120;
    public static final String UNTITLED = "Untitled video";

    private final AssetRepository repository;
    private final MediaSettingsServiceV1 settings;

    public AssetRules(AssetRepository repository, MediaSettingsServiceV1 settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /** The scheduling behaviour (kind) of a project media type; fails when the type isn't enabled for the project. */
    public String kindOf(UUID org, String mediaType) { return settings.require(org, mediaType).kind(); }

    /** A new asset's media type: the given one (default: its kind), which must be enabled and have that kind. */
    public String newAssetType(UUID org, String kind, String mediaType) {
        if (!KINDS.contains(kind)) throw ApiException.bad("Type must be one of " + KINDS);
        String type = mediaType == null ? kind : mediaType;
        if (!kindOf(org, type).equals(kind)) throw ApiException.bad("Media type does not match its scheduling behavior.");
        return type;
    }

    /** Episodes need series/season/episode, unique within the project (checked under a project lock). Call inside a transaction. */
    public void episode(UUID org, String kind, String series, Integer season, Integer episode, UUID except) {
        if (!"EPISODE".equals(kind)) return;
        if (series == null || series.isBlank() || series.trim().length() > TITLE_MAX || season == null || season < 1 || episode == null || episode < 1)
            throw ApiException.bad("Episodes need a series name, a positive season number and a positive episode number.");
        repository.lockProject(org);
        if (repository.episodeTaken(org, series.trim(), season, episode, except))
            throw ApiException.conflict("DUPLICATE_EPISODE", "This series already has that season and episode number.");
    }

    /** A title from an outside source (library, file name, CSV): trimmed, cut to the limit, never blank. */
    public static String importedTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        return t.isEmpty() ? UNTITLED : t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) : t;
    }

    /** One mid-roll cue point in the middle of longer programmes, snapped to a segment boundary. */
    public static String defaultCues(String kind, long dur) {
        if (kind.equals("AD") || dur < 120_000) return "";
        return String.valueOf(Times.snapMs(dur / 2));
    }

    /** Cue points snapped to segment boundaries, inside the video, without duplicates, as stored ("ms,ms,…"). */
    public static String cues(List<Long> points, long durationMs) {
        return String.join(",", points.stream().map(Times::snapMs).filter(c -> c > 0 && (durationMs == 0 || c < durationMs))
                .distinct().sorted().map(String::valueOf).toList());
    }

    /** Licence window and country codes. Blank allowed countries stay blank (rights INCOMPLETE): never worldwide by default. */
    public static void rights(List<String> allowedCountries, OffsetDateTime validFrom, OffsetDateTime validUntil) {
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom))
            throw ApiException.bad("The licence end must be after its start.");
        for (String c : Times.codes(Times.csv(allowedCountries)))
            if (!c.matches("[A-Z]{2}")) throw ApiException.bad("Use 2-letter country codes (or WW for worldwide): " + c);
    }

    static String color(String c) {
        return c != null && c.matches("#[0-9a-fA-F]{6}") ? c : "#6366f1";
    }
}
