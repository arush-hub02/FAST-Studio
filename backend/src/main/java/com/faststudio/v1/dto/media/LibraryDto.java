package com.faststudio.v1.dto.media;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Video library connection administration, library sync and "prepare selected". Tenant answers never name the provider. */
public final class LibraryDto {
    private LibraryDto() {}

    /** Blank token id / secrets keep the stored ones. passthroughTag: only Mux assets with exactly this passthrough are imported (blank = all). */
    public record AccountRequest(@Size(max = 200) String tokenId, @Size(max = 500) String tokenSecret, @Size(max = 500) String webhookSecret,
                                 @Size(max = 120) String environment, @Size(max = 255) String passthroughTag) {}

    /**
     * Never carries a secret. {@code webhookUrl} is what to enter in the Mux environment's webhook settings.
     * healthy: result of the last connection check (null = never checked); checkError says why it failed.
     */
    public record AccountView(boolean connected, String tokenId, String environment, String passthroughTag, boolean webhookSecretSet, String webhookUrl,
                              OffsetDateTime checkedAt, String checkError, Boolean healthy) {}

    /**
     * Where the library sync stands. state: NEVER, QUEUED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED or CANCELLED
     * (the latest sync job). videos: library videos seen in scope so far; added: new videos added to the catalogue;
     * pages: listing pages processed; complete: the last full scan reached its final page. nextRunAt: when a sync
     * waiting for a retry runs again.
     */
    public record SyncStatus(String state, int videos, int added, int pages, boolean complete, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                             String lastError, OffsetDateTime nextRunAt) {}

    /**
     * The project's view of its connected library: whether one is connected and usable, what it can do (CATALOGUE,
     * DIRECT_UPLOAD, SOURCE_FILE, WEBHOOKS), the last check and the library sync.
     */
    public record LibraryView(boolean connected, Boolean healthy, OffsetDateTime checkedAt, List<String> capabilities, SyncStatus sync) {}

    /** Library videos to prepare for broadcast (1 to 100). */
    public record PrepareRequest(@NotNull @NotEmpty @Size(max = 100) List<@NotNull UUID> assetIds) {}

    /** A video that wasn't queued, and why: NOT_FOUND, NOT_FROM_LIBRARY, ALREADY_READY, ALREADY_PROCESSING, NOT_READY_IN_LIBRARY, PROCESSING_LIMIT. */
    public record Skipped(UUID assetId, String reason) {}

    /** How many videos were queued for preparation, and the ones that weren't. */
    public record PrepareResult(int queued, List<Skipped> skipped) {}
}
