package com.faststudio.v1.dto.channels;

import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.dto.programming.ScheduleDto.Programme;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Channels of a project. */
public final class ChannelDto {
    private ChannelDto() {}

    /**
     * A channel card: what is on now/next from the published schedule, how far it is scheduled, the published version,
     * plain-language reasons it can't go live yet (empty = ready) and its fallback video.
     */
    public record ChannelView(Channel channel, Programme now, Programme next, Instant scheduledUntil, Integer publishedVersion,
                              List<String> readiness, String fallbackTitle) {}

    /** {@code assetIds}/{@code autofillHours} optionally auto-fill the new channel's draft. */
    public record CreateRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description, String genre, String language,
                                String timezone, String color, List<String> territories, Integer adSecondsPerHour, Integer breakSeconds,
                                UUID fallbackAssetId, List<UUID> assetIds, Integer autofillHours) {}

    /** Null fields keep their stored value; {@code clearFallback} removes the fallback video. */
    public record UpdateRequest(String name, String description, String genre, String language, String timezone, String color,
                                List<String> territories, Integer adSecondsPerHour, Integer breakSeconds, UUID fallbackAssetId, Boolean clearFallback) {}
}
