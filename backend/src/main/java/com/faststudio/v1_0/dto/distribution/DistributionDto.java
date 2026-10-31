package com.faststudio.v1_0.dto.distribution;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Distribution destinations, delivery checks and sharing channels with partner organizations. */
public final class DistributionDto {
    private DistributionDto() {}

    // ------------------------------------------------------------------ destinations and deliveries

    public record Destination(UUID id, String name, String kind, String adMode, String markerFormat, int maxAdSecondsPerHour,
                              String notes, OffsetDateTime createdAt) {}

    /** One delivery attempt; {@code simulated} = no real partner was contacted. */
    public record Publication(UUID id, UUID channelId, String channel, UUID destinationId, String destination, Integer versionNumber,
                              String status, String message, boolean simulated, OffsetDateTime createdAt) {}

    public record DestinationRequest(@NotBlank @Size(max = 80) String name, @Pattern(regexp = "OWNED_WEB|PARTNER_TV") String kind,
                                     @Pattern(regexp = "SSAI|SGAI|PASSTHROUGH") String adMode, @Pattern(regexp = "DATERANGE|CUE_OUT") String markerFormat,
                                     @Min(0) @Max(1200) int maxAdSecondsPerHour, String notes) {}

    public record Check(String label, boolean passed, String detail) {}

    public record PublishResult(Publication publication, List<Check> checks) {}

    // ------------------------------------------------------------------ partner sharing (customer side)

    public record PartnerOrg(UUID id, String name, String country) {}

    public record Scope(String key, String meaning) {}

    /** Partner organizations on the platform (names only) and what can be shared. */
    public record Directory(List<PartnerOrg> partners, List<Scope> scopes) {}

    public record Named(UUID id, String name) {}

    /** A sharing with one partner, including how often the partner read it in the last 7 days. */
    public record Sharing(UUID id, UUID partnerId, String partnerName, String status, List<String> scopes, boolean allChannels, List<Named> channels,
                          UUID destinationId, String destinationName, String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt,
                          OffsetDateTime revokedAt, OffsetDateTime expiresAt, long readsLast7Days, OffsetDateTime lastReadAt) {}

    public record ShareRequest(UUID partnerOrgId, Boolean allChannels, List<UUID> channelIds, @NotNull List<String> scopes, UUID destinationId,
                               String note, OffsetDateTime expiresAt) {
        public boolean all() { return Boolean.TRUE.equals(allChannels); }
    }
}
