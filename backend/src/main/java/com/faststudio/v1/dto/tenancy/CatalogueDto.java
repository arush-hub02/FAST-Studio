package com.faststudio.v1.dto.tenancy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The global catalogue (docs/PLATFORM_ADMIN_PLAN.md): Module ─ Feature group ─ Feature ─ uses → Permission(s).
 * Keys are identifiers: capitals, digits and underscores, starting with a letter; they never change.
 */
public final class CatalogueDto {
    private CatalogueDto() {}

    public static final String KEY = "[A-Z][A-Z0-9_]{1,63}";

    public record ModuleEntry(String key, String name, String description, int position, boolean enabled) {}

    /** published: offered to projects and shown in the role editor. */
    public record Group(String key, String moduleKey, String name, String description, int position, boolean published) {}

    /**
     * A feature: switched on per project (projects link it); screens hide its parts when it's off. permissions: the
     * permission keys it uses. isDefault: linked by "add default features"; hidden: not offered to projects; beta: shown as beta.
     */
    public record Feature(String key, String name, String description, String groupKey, List<String> permissions,
                          boolean isDefault, boolean hidden, boolean beta, boolean enabled, boolean builtIn) {}

    /** A permission: given to people through roles. builtIn = enforced by the server (system). */
    public record Permission(String key, String name, String description, String category, boolean enabled, boolean builtIn) {}

    /** Everything at once, for the configuration screens and the role editor. */
    public record Catalogue(List<ModuleEntry> modules, List<Group> groups, List<Feature> features, List<Permission> permissions) {}

    public record ModuleRequest(@NotBlank @Pattern(regexp = KEY) String key, @NotBlank @Size(max = 80) String name,
                                @Size(max = 300) String description, Integer position, Boolean enabled) {}

    public record GroupRequest(@NotBlank @Pattern(regexp = KEY) String key, @NotBlank @Pattern(regexp = KEY) String moduleKey,
                               @NotBlank @Size(max = 80) String name, @Size(max = 300) String description, Integer position, Boolean published) {}

    public record FeatureRequest(@NotBlank @Pattern(regexp = KEY) String key, @NotBlank @Size(max = 80) String name,
                                 @Size(max = 300) String description, @NotBlank @Pattern(regexp = KEY) String groupKey,
                                 @NotNull List<@Pattern(regexp = KEY) String> permissions,
                                 Boolean isDefault, Boolean hidden, Boolean beta, Boolean enabled) {}

    public record PermissionRequest(@NotBlank @Pattern(regexp = KEY) String key, @NotBlank @Size(max = 80) String name,
                                    @Size(max = 300) String description, @Size(max = 40) String category, Boolean enabled) {}

    /** A project's view: the catalogue it may link from, what it has linked, and the keys that apply to the caller now. */
    public record ProjectFeatures(List<ModuleEntry> modules, List<Group> groups, List<Feature> available, List<String> linked,
                                  List<String> features, List<String> permissions) {}
}
