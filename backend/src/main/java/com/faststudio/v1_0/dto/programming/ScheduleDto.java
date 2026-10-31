package com.faststudio.v1_0.dto.programming;

import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.common.Data.Entry;
import com.faststudio.v1_0.core.common.Data.Version;
import com.faststudio.v1_0.services.programming.Planner;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Channel schedule: one editable draft (version 0) and immutable published versions 1..n.
 * Every draft edit carries {@code expectedRevision}; a stale editor gets 412 instead of overwriting someone else.
 */
public final class ScheduleDto {
    private ScheduleDto() {}

    /** Lightweight asset info for drawing the timeline. */
    public record AssetRef(UUID id, String title, String kind, String color, String mediaKey, long durationMs, String status) {}

    /** Draft and published entries within [from, to), validation issues and version history. */
    public record View(Data.Channel channel, Version draft, List<Entry> draftEntries, Version published, List<Entry> publishedEntries,
                       List<Planner.Issue> issues, List<Version> versions, Map<UUID, AssetRef> assets, boolean unpublishedChanges,
                       Instant now, Instant from, Instant to) {}

    public record AutofillRequest(int expectedRevision, Integer hours, List<UUID> assetIds, boolean replace) {}

    /** New draft revision and plain-language notices for the operator. */
    public record EditResult(int revision, List<String> notices) {}

    /** kind AD_BREAK adds a break; otherwise a programme (by default with its mid-roll breaks). */
    public record AddRequest(int expectedRevision, String kind, UUID assetId, Instant startAt, Integer seconds, Boolean withBreaks) {}

    public record RevisionRequest(int expectedRevision) {}

    public record RestoreRequest(int expectedRevision, int versionNumber) {}

    public record PublishRequest(int expectedRevision, String note) {}

    public record PublishResult(int versionNumber, List<Planner.Issue> warnings) {}

    /** One programme in the guide (consecutive parts of a video and its mid-rolls merged). */
    public record Programme(UUID assetId, String title, String description, String genre, String kind, String color, String mediaKey,
                            Instant start, Instant end, int adBreaks, boolean now, String thumbUrl, String series, Integer season, Integer episode) {
        public Programme withThumb(String url) {
            return new Programme(assetId, title, description, genre, kind, color, mediaKey, start, end, adBreaks, now, url, series, season, episode);
        }
    }
}
