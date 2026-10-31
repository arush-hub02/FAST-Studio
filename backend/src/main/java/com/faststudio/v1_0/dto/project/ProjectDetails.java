package com.faststudio.v1_0.dto.project;

import java.util.List;
import java.util.UUID;

/** Explicit browser-safe projection; never add provider credentials or raw configuration objects here. */
public record ProjectDetails(int schemaVersion, Project project, Access access, Media media,
                             Connectors connectors, Summary summary,
                             com.faststudio.v1_0.dto.tenancy.TenantConfiguration.Settings configuration) {
    public record Project(UUID id, String slug, String name, String kind, String status) {}
    public record Access(String role, List<String> permissions, List<String> features) {}
    public record MediaType(String key, String label, String kind) {}
    public record Media(List<MediaType> types, List<String> enabledTypes, String defaultType,
                        String premiereTime, List<Integer> weekdays, boolean personalizedAds,
                        boolean viewerIntegrationConfigured, int revision) {}
    public record Library(String provider, String state, String environment, boolean credentialsConfigured,
                          boolean webhookConfigured, List<String> capabilities) {}
    public record Advertising(UUID id, String name, String kind, boolean enabled, String managedBy, boolean configured) {}
    public record Delivery(String origin, String cdn, boolean configured) {}
    public record Connectors(List<Library> media, List<Advertising> advertising, Delivery delivery) {}
    public record Summary(long assets, long readyAssets, long channels) {}
}
