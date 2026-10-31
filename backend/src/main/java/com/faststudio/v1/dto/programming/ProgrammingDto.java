package com.faststudio.v1.dto.programming;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Smart programming rules: a series placed daily in episode order on chosen weekdays. */
public final class ProgrammingDto {
    private ProgrammingDto() {}

    /** A stored smart-programming rule. */
    public record Rule(UUID id, UUID orgId, UUID channelId, String series, LocalDate startDate, LocalDate endDate,
                       LocalTime premiereTime, LocalTime repeatTime, String weekdays, int startSeason, int startEpisode,
                       boolean loop, boolean enabled) {}

    /** The episode a rule already placed on one day. */
    public record Occurrence(LocalDate airDate, UUID assetId) {}

    /** Premiere (and optional same-day repeat) times are wall-clock times in the channel's timezone, on 5-second boundaries. */
    public record RuleRequest(@NotBlank @Size(max = 120) String series, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                              @NotBlank String premiereTime, String repeatTime, @NotNull List<Integer> weekdays,
                              @Min(1) int startSeason, @Min(1) int startEpisode, boolean loop) {}

    public record StatusRequest(boolean enabled) {}

    /** Generate placements into the draft for the next {@code days}; {@code expectedRevision} guards concurrent edits. */
    public record GenerateRequest(int expectedRevision, @Min(1) @Max(31) int days) {}
}
