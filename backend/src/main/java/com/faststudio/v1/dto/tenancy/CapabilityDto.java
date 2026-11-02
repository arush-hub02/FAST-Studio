package com.faststudio.v1.dto.tenancy;

import java.util.UUID;

/** A row of the features and permissions catalogue, for labels. Editing goes through {@link CatalogueDto}. */
public final class CapabilityDto {
    private CapabilityDto() {}

    /** kind FEATURE or PERMISSION; orgId is always null since V25; builtIn = enforced by the server. */
    public record Capability(String key, String kind, String name, String description, String category, UUID orgId, boolean builtIn, boolean enabled) {}
}
