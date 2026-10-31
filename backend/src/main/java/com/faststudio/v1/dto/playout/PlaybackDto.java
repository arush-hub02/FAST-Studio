package com.faststudio.v1.dto.playout;

import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.dto.programming.ScheduleDto.Programme;
import com.faststudio.v1.services.rights.Rights;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Public viewer-side playback: sessions, delivery beacons and the player overlay. */
public final class PlaybackDto {
    private PlaybackDto() {}

    /** {@code country} is an explicit, labelled simulation; production would take it from a trusted edge/geo-IP header. */
    public record StartRequest(@NotBlank String org, @NotBlank String channel, UUID destinationId,
                               @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country, String viewerToken) {}

    /** {@code playlistUrl} is the Worker's live HLS URL carrying a signed session token. */
    public record Started(UUID sessionId, String playlistUrl, String adMode, String markerFormat, String destination,
                          String country, boolean simulatedLocation, Rights.Decision rightsNow) {}

    public record Beacon(UUID breakId, UUID campaignId, @NotBlank String event) {}

    public record BreakInfo(UUID id, Instant start, Instant end, String ownership, boolean filledByUs, String scteOut,
                            AdDecisionView decision) {}

    public record EventRow(String event, String campaign, int seconds, OffsetDateTime occurredAt) {}

    /** What is on now/next, the current and upcoming breaks, rights now and the session's last delivery events. */
    public record Info(String channel, String channelStatus, String destination, String adMode, String markerFormat, String country,
                       Programme now, Programme next, BreakInfo currentBreak,
                       List<BreakInfo> upcomingBreaks, Rights.Decision rightsNow, List<EventRow> events, Instant serverTime) {}
}
