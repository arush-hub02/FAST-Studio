package com.faststudio.v1_0.dto.tenancy;

import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request and response records for the tenant model.
 *
 * <p>Hierarchy: <b>customer tenant</b> (subscription, connector grants, tenant defaults)
 * owns one or more <b>projects</b> (isolated content workspaces; stored as CUSTOMER rows in
 * {@code organization}). Partner organizations are not tenants and never appear here.
 *
 * <p>Configuration resolves global default → tenant value → project override. Secret values are
 * write-only: responses report only whether a secret is configured.
 */
public final class TenantConfiguration {
    private TenantConfiguration() {}

    /** A customer tenant with its subscription and current usage across all its projects. */
    public record Account(UUID id, String slug, String name, String status, String planKey, String subscriptionStatus,
                          int revision, long projects, long channels) {}

    /** One project (content workspace) of a tenant. */
    public record Project(UUID id, UUID tenantId, String slug, String name, String status, long members) {}

    /** Subscription plan defined by the platform; allowances apply across the whole tenant. */
    public record Plan(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,49}") String key,
                       @NotBlank @Size(max = 100) String name,
                       boolean enabled,
                       @Min(1) @Max(10000) int maxProjects,
                       @Min(0) @Max(100000) int maxChannels,
                       @NotNull @DecimalMin("0") @DecimalMax("999999999999.99") BigDecimal monthlyPrice,
                       @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                       @NotNull @Size(max = 100) List<String> features) {}

    /** Global connector definition. The driver selects the server adapter and cannot change later. */
    public record Connector(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,49}") String key,
                            @NotBlank @Size(max = 100) String name,
                            @NotNull @Pattern(regexp = "MEDIA|ADS|STORAGE|OTHER") String category,
                            @NotNull @Pattern(regexp = "MUX|VAST|R2|CUSTOM") String driver,
                            boolean enabled,
                            @Size(max = 500) String description) {}

    /** Global custom property definition (create or update). */
    public record Property(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{1,79}") String key,
                           @NotBlank @Size(max = 100) String label,
                           @NotNull @Pattern(regexp = "STRING|NUMBER|BOOLEAN|SELECT") String valueType,
                           String connectorKey,
                           boolean secret,
                           boolean projectOverride,
                           boolean enabled,
                           @NotNull @Size(max = 100) List<@NotBlank @Size(max = 200) String> options,
                           JsonNode defaultValue,
                           Integer expectedRevision) {}

    /** Property definition as returned to clients; a secret default is never included. */
    public record PropertyView(String key, String label, String valueType, String connectorKey, boolean secret,
                               boolean projectOverride, boolean enabled, List<String> options, JsonNode defaultValue,
                               boolean defaultConfigured, int revision, boolean system) {}

    /**
     * A property's resolved value for one scope.
     * {@code source} is PROJECT, TENANT, GLOBAL or UNSET; {@code overridden} means the current scope stores its own value.
     */
    public record EffectiveProperty(PropertyView definition, JsonNode value, boolean configured, String source, boolean overridden) {}

    /** Connector as seen by a tenant or project: allowed = granted to the tenant; enabled = usable in this scope. */
    public record ConnectorState(Connector definition, boolean allowed, boolean enabled, boolean overridden) {}

    /** Everything a tenant/project settings screen needs. {@code project} is null for the tenant scope. */
    public record Settings(Account tenant, Project project, Plan plan, List<Project> projects,
                           List<EffectiveProperty> properties, List<ConnectorState> connectors, boolean editable) {}

    public record Subscription(@NotNull String planKey,
                               @NotNull @Pattern(regexp = "TRIAL|ACTIVE|PAUSED|CANCELLED") String status,
                               @Min(0) int expectedRevision) {}

    /** Set a property value, or {@code reset=true} to remove the override and inherit again. */
    public record Value(JsonNode value, Boolean reset) {}

    /** Enable/disable a connector in a scope, or {@code reset=true} to inherit. */
    public record Enable(Boolean enabled, Boolean reset) {}

    public record CreateProject(@NotBlank @Size(max = 80) String name,
                                @NotBlank @Pattern(regexp = "[a-z0-9-]{3,40}") String slug,
                                @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
                                @NotBlank String timezone,
                                @NotNull UUID ownerUserId) {}

    /** Candidate owner for a new project: an existing owner/admin somewhere in the tenant. */
    public record Owner(UUID id, String name, String email) {}
}
