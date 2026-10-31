package com.faststudio.v1.dto.partner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What partner organizations (people and API clients) read. Fixed shapes only — never internal objects — so new
 * internal fields can't leak to partners.
 */
public final class PartnerDto {
    private PartnerDto() {}

    // ------------------------------------------------------------------ sharings from customers

    public record Named(UUID id, String name) {}

    /** The partner's delivery settings chosen by the content owner. */
    public record Destination(String name, String adMode, String markerFormat, int maxAdSecondsPerHour) {}

    public record Shared(UUID id, UUID ownerId, String ownerSlug, String ownerName, String status, List<String> scopes, boolean allChannels,
                         List<Named> channels, Destination destination, String note, OffsetDateTime createdAt, OffsetDateTime acceptedAt,
                         OffsetDateTime expiresAt) {}

    // ------------------------------------------------------------------ shared data

    public record PartnerChannel(UUID id, String slug, String name, String description, String genre, String language, String timezone,
                                 String color, List<String> countries, boolean live) {}

    /** {@code image} is an absolute, signed thumbnail URL. */
    public record Programme(String title, String description, String genre, Instant start, Instant end, boolean onNow, int adBreaks, String image) {}

    /** How the partner starts one playback session per viewer. */
    public record Stream(String howTo, String method, String url, Map<String, Object> body, Destination destination) {}

    /** An upcoming ad break with its SCTE-35 splice markers (hex). */
    public record AdBreak(UUID id, Instant start, Instant end, long durationMs, String ownership, String scte35Out, String scte35In) {}

    public record Day(LocalDate day, long viewerSessions, long adImpressions) {}

    /** Counts for the partner's own destination only, never other platforms' viewers. */
    public record Delivery(String destination, int days, long viewerSessions, long adImpressions, long adCompletes, List<Day> byDay, String note) {}

    // ------------------------------------------------------------------ API clients

    public record Client(UUID id, String name, List<String> scopes, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {}

    public record NewClient(@NotBlank @Size(max = 60) String name, List<String> scopes) {}

    /** {@code clientSecret} is shown only in this response; only its SHA-256 is stored. */
    public record Created(UUID clientId, String clientSecret, List<String> scopes, String tokenUrl, String note) {}
}
