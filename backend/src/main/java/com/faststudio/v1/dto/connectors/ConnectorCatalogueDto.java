package com.faststudio.v1.dto.connectors;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The connector marketplace (docs/PLATFORM_ADMIN_PLAN.md, P3): Connector category ─ Connector ─ Property definitions;
 * instances hold the values. Encrypted values never leave the server: views only say whether they are configured.
 */
public final class ConnectorCatalogueDto {
    private ConnectorCatalogueDto() {}

    public record Category(String key, String name, String description, int position) {}

    public record CategoryRequest(@Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String key, @NotBlank @Size(max = 80) String name,
                                  @Size(max = 300) String description, Integer position) {}

    /** A field of a connector. scope EXTERNAL (shown to tenants) or INTERNAL (platform only). */
    public record Property(String connectorKey, String key, String label, String description, String valueType, List<String> options,
                           String defaultValue, int position, boolean required, boolean encrypted, String scope) {}

    public record PropertyRequest(@Pattern(regexp = "[a-z][a-z0-9_]{1,63}") String key, @NotBlank @Size(max = 100) String label,
                                  @Size(max = 300) String description, @NotNull @Pattern(regexp = "STRING|NUMBER|BOOLEAN|SELECT|URL") String valueType,
                                  List<@Size(max = 100) String> options, @Size(max = 500) String defaultValue, Integer position,
                                  boolean required, boolean encrypted, @NotNull @Pattern(regexp = "EXTERNAL|INTERNAL") String scope) {}

    /**
     * A connector with everything the marketplace shows. status AVAILABLE, UPCOMING or UNPUBLISHED; tenantUpdate ALLOWED,
     * NOT_ALLOWED or CONTACT_ACCOUNT_MANAGER; level PLATFORM or PROJECT; multiInstance: several per project (ad servers).
     */
    public record Connector(String key, String name, String category, String driver, boolean enabled, String description, String longDescription,
                            String logoUrl, String darkLogoUrl, String referenceLink, String tier, String status, String tenantUpdate,
                            String requiredPermission, String level, boolean multiInstance, List<Property> properties) {}

    /** The marketplace details of a connector (name, category, driver and switch stay in the connector definition save). */
    public record DetailsRequest(@Size(max = 4000) String longDescription, @Size(max = 500) String logoUrl, @Size(max = 500) String darkLogoUrl,
                                 @Size(max = 500) String referenceLink, @NotNull @Pattern(regexp = "FREE|PRO") String tier,
                                 @NotNull @Pattern(regexp = "AVAILABLE|UPCOMING|UNPUBLISHED") String status,
                                 @NotNull @Pattern(regexp = "ALLOWED|NOT_ALLOWED|CONTACT_ACCOUNT_MANAGER") String tenantUpdate,
                                 @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String requiredPermission,
                                 @NotNull @Pattern(regexp = "PLATFORM|PROJECT") String level, boolean multiInstance) {}

    /** Platform view: categories and every connector with its properties. */
    public record Catalogue(List<Category> categories, List<Connector> connectors) {}

    /** A value as shown: the value itself, or for an encrypted property only whether one is stored. */
    public record Value(String value, boolean configured) {}

    /** One configured use of a connector. connected: enabled and every required property has a value; missing names the rest. */
    public record Instance(UUID id, String name, int position, boolean enabled, boolean connected, List<String> missing,
                           Map<String, Value> values, OffsetDateTime checkedAt, String checkError) {}

    /**
     * A connector as one project (or the platform, for PLATFORM-level ones) sees it: the connector (properties limited to
     * external ones for tenants), its instances, and whether the caller may connect it here.
     */
    public record ConnectorState(Connector connector, List<Instance> instances, boolean connected, boolean canConnect, String note) {}

    /** The project's (or platform's) connectors by category. */
    public record Marketplace(List<Category> categories, List<ConnectorState> connectors) {}

    /**
     * Create or update an instance. values: property key → value; a blank value for an encrypted property keeps the
     * stored one, a blank value for any other property clears it.
     */
    public record InstanceRequest(@Size(max = 100) String name, Boolean enabled, @NotNull Map<@NotBlank String, @Size(max = 4000) String> values) {}
}
