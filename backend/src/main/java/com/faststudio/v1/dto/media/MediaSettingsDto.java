package com.faststudio.v1.dto.media;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.util.List;

/** Media type catalogue and per-project media/programming settings. */
public final class MediaSettingsDto {
    private MediaSettingsDto() {}

    /** Platform media type; {@code kind} (MOVIE/EPISODE/SHORT/AD) decides scheduling behavior and never changes. */
    public record Type(String key, String label, String kind, boolean enabled) {}

    /** Stored {@code tenant_settings} row of a project (V15: premiere/weekdays/personalized are mirrors, not read). */
    public record Row(String enabledTypes, String defaultType, LocalTime premiereTime, String weekdays,
                      boolean personalizedAds, String viewerSecret, int revision) {}

    /** Resolved settings shown to the project; the viewer key itself is never returned. */
    public record View(List<Type> types, List<String> enabledTypes, String defaultType, String premiereTime,
                       List<Integer> weekdays, boolean personalizedAds, boolean viewerKeySet, int revision) {}

    public record TypeRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String key,
                              @NotBlank @Size(max = 80) String label,
                              @Pattern(regexp = "MOVIE|EPISODE|SHORT|AD") String kind,
                              boolean enabled) {}

    public record Entitlements(@NotNull List<String> types) {}

    public record SettingsRequest(@NotNull List<String> enabledTypes, @NotBlank String defaultType,
                                  @NotBlank String premiereTime, @NotNull List<Integer> weekdays,
                                  boolean personalizedAds, int expectedRevision) {}

    /** Shared secret viewer apps use to sign viewer context; write-only. */
    public record ViewerKeyRequest(@NotBlank @Size(min = 32, max = 256) @Pattern(regexp = "[A-Za-z0-9_-]+")
                                   @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String key) {
        @Override public String toString() { return "ViewerKeyRequest[key=REDACTED]"; }
    }
}
