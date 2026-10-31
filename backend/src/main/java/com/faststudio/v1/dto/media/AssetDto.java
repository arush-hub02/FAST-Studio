package com.faststudio.v1.dto.media;

import com.faststudio.v1.core.common.Data.Asset;
import com.faststudio.v1.services.rights.Rights;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Media library (assets) and rights requests/responses. */
public final class AssetDto {
    private AssetDto() {}

    /** What a processing job needs to know about one asset (looked up by id, across tenants). */
    public record ProcessingJob(UUID id, UUID orgId, String slug, String title, String kind, String color, String generator, long durationMs,
                                String mediaKey, String sourceUrl, String sourceRef) {}

    /**
     * An asset as the library shows it: signed thumbnail/preview URLs, its rights policy, a rights status
     * (OK, EXPIRING, EXPIRED, INCOMPLETE, MISSING or N/A for ads) and the channels it airs on.
     */
    public record AssetView(Asset asset, String thumbUrl, String previewUrl, Rights.Policy rights, String rightsStatus,
                            int upcomingAirings, List<String> channels) {}

    /** With {@code sampleSeconds} set, ffmpeg generates a fictional demo video; otherwise the asset waits in DRAFT for an upload. */
    public record CreateRequest(@NotBlank @Size(max = 120) String title, @Size(max = 2000) String description, @NotBlank String kind,
                                String genre, String language, String series, Integer season, Integer episode, String color,
                                Integer sampleSeconds, String sampleTagline, String mediaType) {}

    /** Null fields keep their stored value. */
    public record UpdateRequest(@Size(max = 120) String title, @Size(max = 2000) String description, String genre, String language,
                                List<Long> cuePoints, String series, Integer season, Integer episode, String mediaType) {}

    public record RightsRequest(List<String> allowedCountries, List<String> blockedCountries, OffsetDateTime validFrom,
                                OffsetDateTime validUntil, boolean adsAllowed, String notes) {}

    /** "Can this play in country X at time T (optionally on a channel)?" */
    public record CheckRequest(@NotNull UUID assetId, @NotBlank String country, UUID channelId, Instant at, Integer minutes) {}

    /** {@code simulated}: the country is an input, not a geo-IP lookup. */
    public record CheckResult(Rights.Decision decision, String asset, String channel, Instant from, Instant to, boolean simulated) {}
}
