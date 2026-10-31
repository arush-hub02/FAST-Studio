package com.faststudio.v1_0.controllers.studio.media;

import com.faststudio.v1_0.core.auth.Auth;
import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.common.Secrets;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.services.media.MediaSettingsServiceV1_0;
import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;

import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class MediaSettingsControllerV1_0 {
    private final Data data;
    private final MediaSettingsServiceV1_0 settings;
    private final Audit audit;
    private final Secrets secrets;
    private final TenantConfigurationServiceV1_0 configuration;
    private final JsonMapper json;
    public MediaSettingsControllerV1_0(Data data, MediaSettingsServiceV1_0 settings, Audit audit, Secrets secrets,
                                   TenantConfigurationServiceV1_0 configuration, JsonMapper json) {
        this.data = data; this.settings = settings; this.audit = audit; this.secrets = secrets;
        this.configuration = configuration; this.json = json;
    }
    @GetMapping("/api/platform/media-types")
    List<MediaSettingsServiceV1_0.Type> types() { return settings.catalog(); }

    public record TypeRequest(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,39}") String key,
                              @NotBlank @Size(max=80) String label, @Pattern(regexp="MOVIE|EPISODE|SHORT|AD") String kind, boolean enabled) {}
    @PostMapping("/api/platform/media-types")
    @Transactional
    List<MediaSettingsServiceV1_0.Type> saveType(@Valid @RequestBody TypeRequest r) {
        if (r.kind() == null) throw ApiException.bad("Choose scheduling behavior for this type.");
        var prior = settings.catalog().stream().filter(t -> t.key().equals(r.key())).findFirst();
        if (prior.isPresent() && !prior.get().kind().equals(r.kind())) throw ApiException.bad("An existing type's scheduling behavior cannot change. Create another type.");
        data.db.sql("insert into media_type(key,label,kind,enabled) values (:k,:l,:b,:e) on conflict (key) do update set label=:l, enabled=:e")
                .param("k", r.key()).param("l", r.label().trim()).param("b", r.kind()).param("e", r.enabled()).update();
        audit.platform(Auth.userId(), "media-type.save", "media_type", null, r.key());
        return types();
    }
    @GetMapping("/api/platform/orgs/{orgId}/media-types")
    List<String> entitled(@PathVariable UUID orgId) { customer(orgId); return settings.entitlements(orgId); }
    public record Entitlements(@NotNull List<String> types) {}
    @PutMapping("/api/platform/orgs/{orgId}/media-types")
    List<String> entitle(@PathVariable UUID orgId, @Valid @RequestBody Entitlements r) {
        customer(orgId);
        Set<String> known = new HashSet<>(settings.catalog().stream().map(MediaSettingsServiceV1_0.Type::key).toList());
        if (r.types().isEmpty() || !known.containsAll(r.types())) throw ApiException.bad("Choose supported media types.");
        data.db.sql("update organization set media_entitlements=:t where id=:o").param("t", String.join(",", r.types().stream().distinct().toList())).param("o", orgId).update();
        audit.platform(Auth.userId(), "media-types.entitle", "organization", orgId, String.join(",", r.types()));
        return entitled(orgId);
    }
    private void customer(UUID org) {
        if (data.db.sql("select count(*) from organization where id=:o and kind='CUSTOMER'").param("o", org).query(Integer.class).single() == 0) throw ApiException.notFound("Organization");
    }
    @GetMapping("/api/orgs/{org}/settings")
    MediaSettingsServiceV1_0.View view(Tenant t) { return settings.view(t.orgId()); }
    public record SettingsRequest(@NotNull List<String> enabledTypes, @NotBlank String defaultType,
                                  @NotBlank String premiereTime, @NotNull List<Integer> weekdays,
                                  boolean personalizedAds, int expectedRevision) {}
    @PutMapping("/api/orgs/{org}/settings")
    @Transactional
    MediaSettingsServiceV1_0.View save(Tenant t, @Valid @RequestBody SettingsRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        data.db.sql("select id from organization where id=:o for update").param("o", t.orgId()).query(UUID.class).single();
        MediaSettingsServiceV1_0.View current = settings.view(t.orgId());
        if (current.revision() != r.expectedRevision()) throw ApiException.conflict("STALE", "Settings changed. Reload before saving.");
        List<String> known = current.types().stream().map(MediaSettingsServiceV1_0.Type::key).toList();
        if (r.enabledTypes().isEmpty() || !known.containsAll(r.enabledTypes()) || !r.enabledTypes().contains(r.defaultType()))
            throw ApiException.bad("Enable supported media types and choose one as the default.");
        data.db.sql("""
            insert into tenant_settings(org_id,enabled_types,default_type,premiere_time,weekdays,personalized_ads)
            values (:o,:types,:def,:at,:days,:ads) on conflict(org_id) do update set enabled_types=:types,
            default_type=:def,premiere_time=:at,weekdays=:days,personalized_ads=:ads,revision=tenant_settings.revision+1""")
                .param("o", t.orgId()).param("types", String.join(",", r.enabledTypes().stream().distinct().toList())).param("def", r.defaultType())
                .param("at", MediaSettingsServiceV1_0.time(r.premiereTime())).param("days", MediaSettingsServiceV1_0.daysCsv(r.weekdays())).param("ads", r.personalizedAds()).update();
        // The three columns above are a rollback mirror only; the product reads these dynamic properties (V15).
        // Only changed values become project overrides, so untouched fields keep inheriting tenant/global defaults.
        configuration.writeProjectValueIfChanged(t.orgId(), TenantConfigurationServiceV1_0.PREMIERE_TIME,
                json.getNodeFactory().stringNode(MediaSettingsServiceV1_0.time(r.premiereTime()).toString()));
        configuration.writeProjectValueIfChanged(t.orgId(), TenantConfigurationServiceV1_0.WEEKDAYS,
                json.getNodeFactory().stringNode(MediaSettingsServiceV1_0.daysCsv(r.weekdays())));
        configuration.writeProjectValueIfChanged(t.orgId(), TenantConfigurationServiceV1_0.PERSONALIZED_ADS,
                json.getNodeFactory().booleanNode(r.personalizedAds()));
        audit.log(t, "settings.update", "organization", t.orgId(), "Media, programming and viewer advertising defaults");
        return view(t);
    }
    public record ViewerKeyRequest(@NotBlank @Size(min=32, max=256)
            @Pattern(regexp="[A-Za-z0-9_-]+")
            @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String key) {
        @Override public String toString() { return "ViewerKeyRequest[key=REDACTED]"; }
    }
    @PostMapping("/api/orgs/{org}/settings/viewer-key")
    @Transactional
    MediaSettingsServiceV1_0.View viewerKey(Tenant t, @Valid @RequestBody ViewerKeyRequest request) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        if (settings.row(t.orgId()) == null) throw ApiException.bad("Save your settings before creating an integration key.");
        data.db.sql("update tenant_settings set viewer_secret=:s,revision=revision+1 where org_id=:o")
                .param("s", secrets.seal(request.key())).param("o", t.orgId()).update();
        data.db.sql("update playback_session set viewer_key=null,audiences='',personalized_until=null where org_id=:o").param("o",t.orgId()).update();
        audit.log(t, "viewer-key.rotate", "organization", t.orgId(), "Previous viewer context tokens are revoked for future sessions");
        return settings.view(t.orgId());
    }
}
