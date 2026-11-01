package com.faststudio.v1.dto.tenancy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The features and permissions catalogue (capability). Keys are the identifiers the frontend checks. */
public final class CapabilityDto {
    private CapabilityDto() {}

    /**
     * One feature or permission. kind FEATURE or PERMISSION; orgId null = global catalogue, else one project's own;
     * builtIn = enforced by the server and can't be removed.
     */
    public record Capability(String key, String kind, String name, String description, String category, UUID orgId, boolean builtIn, boolean enabled) {}

    /** A new entry. key: capitals, digits and underscores, starting with a letter (e.g. CLIPS_EXPORT); unique across the platform. */
    public record CreateRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String key,
                                @NotBlank @Pattern(regexp = "FEATURE|PERMISSION") String kind,
                                @NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
                                @Size(max = 40) String category, Boolean enabled) {}

    /** Changes to an entry; the key and kind never change. Built-in entries can be renamed but not switched off. */
    public record UpdateRequest(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
                                @Size(max = 40) String category, Boolean enabled) {}

    /** A project's view: the catalogue it can use, and which keys are effective for it now. */
    public record ProjectView(List<Capability> catalogue, List<String> features, List<String> permissions) {}
}
