package com.faststudio.v1_0.dto.media;

import jakarta.validation.constraints.Size;

/** Video library connector (Mux) administration and sync results. */
public final class LibraryDto {
    private LibraryDto() {}

    /** Blank token id / secrets keep the stored ones. passthroughTag: only Mux assets with exactly this passthrough are imported (blank = all). */
    public record AccountRequest(@Size(max = 200) String tokenId, @Size(max = 500) String tokenSecret, @Size(max = 500) String webhookSecret,
                                 @Size(max = 120) String environment, @Size(max = 255) String passthroughTag) {}

    /** Never carries a secret. {@code webhookUrl} is what to enter in the Mux environment's webhook settings. */
    public record AccountView(boolean connected, String tokenId, String environment, String passthroughTag, boolean webhookSecretSet, String webhookUrl) {}

    /** Videos found in the library and how many new assets were queued for packaging. */
    public record SyncResult(int videos, int added) {}
}
