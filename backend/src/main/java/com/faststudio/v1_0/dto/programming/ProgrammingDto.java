package com.faststudio.v1_0.dto.programming;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

/** Smart programming rules: a series placed daily in episode order on chosen weekdays. */
public final class ProgrammingDto {
    private ProgrammingDto() {}

    /** Premiere (and optional same-day repeat) times are wall-clock times in the channel's timezone, on 5-second boundaries. */
    public record RuleRequest(@NotBlank @Size(max = 120) String series, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                              @NotBlank String premiereTime, String repeatTime, @NotNull List<Integer> weekdays,
                              @Min(1) int startSeason, @Min(1) int startEpisode, boolean loop) {}

    public record StatusRequest(boolean enabled) {}

    /** Generate placements into the draft for the next {@code days}; {@code expectedRevision} guards concurrent edits. */
    public record GenerateRequest(int expectedRevision, @Min(1) @Max(31) int days) {}
}
