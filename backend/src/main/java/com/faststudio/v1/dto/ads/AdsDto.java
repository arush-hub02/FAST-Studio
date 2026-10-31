package com.faststudio.v1.dto.ads;

import com.faststudio.v1.dto.media.AdDecisionView;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Direct-sold campaigns and ad-decision simulation of a project. */
public final class AdsDto {
    private AdsDto() {}

    /** A campaign as the ad decision sees it: schedule, targeting and its creative's delivery state. */
    public record Candidate(UUID id, String name, String advertiser, String status, OffsetDateTime startsAt, OffsetDateTime endsAt,
                            String territories, int priority, BigDecimal cpm, String creativeStatus, String mediaKey, long durationMs,
                            String audiences, int frequencyCap) {}

    /** Our conditioned copy of an external ad server's creative (id null when it could not be accepted). */
    public record Creative(UUID id, String status, String mediaKey, long durationMs) {}

    /** A campaign with its creative and delivery counts; a completion only counts when its start was also recorded. */
    public record Campaign(UUID id, String name, String advertiser, String status, OffsetDateTime startsAt, OffsetDateTime endsAt,
                           String territories, int priority, BigDecimal cpm, UUID creativeAssetId, String creativeTitle,
                           String creativeStatus, long creativeDurationMs, String creativeColor, String mediaKey,
                           long impressions, long completes, String thumbUrl, String audiences, int frequencyCap) {
        public Campaign withThumb(String url) {
            return new Campaign(id, name, advertiser, status, startsAt, endsAt, territories, priority, cpm, creativeAssetId, creativeTitle,
                    creativeStatus, creativeDurationMs, creativeColor, mediaKey, impressions, completes, url, audiences, frequencyCap);
        }
    }

    /** {@code territories} empty = worldwide; {@code frequencyCap} 0 = uncapped. */
    public record CampaignRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 80) String advertiser, @NotNull UUID creativeAssetId,
                                  @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt, List<String> territories,
                                  @Min(1) @Max(10) int priority, @NotNull @DecimalMin("0") @DecimalMax("500") BigDecimal cpm,
                                  List<String> audiences, @Min(0) @Max(100) Integer frequencyCap) {}

    public record StatusRequest(@Pattern(regexp = "ACTIVE|PAUSED|ENDED") String status) {}

    public record SimulateRequest(@NotNull UUID channelId, @NotBlank String country) {}

    /** The decision for the channel's next published break, with a reason for every campaign left out. */
    public record Simulation(Instant breakStart, long breakSeconds, String ownership, String programme, AdDecisionView decision, boolean simulated) {}
}
