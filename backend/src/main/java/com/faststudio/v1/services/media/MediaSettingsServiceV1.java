package com.faststudio.v1.services.media;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.MediaSettingsDto.*;
import com.faststudio.v1.repositories.media.MediaSettingsRepository;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;
import java.util.*;

/**
 * Media and programming settings of a project, plus the platform's media type catalogue.
 *
 * <p>Media types (platform catalogue + project entitlement) and the viewer key stay in {@code tenant_settings}.
 * Premiere time, weekdays and personalized ads are dynamic properties since V15: they resolve
 * project override → tenant value → global default through {@link TenantConfigurationServiceV1#effective}.
 */
@Service
public class MediaSettingsServiceV1 {
    private final MediaSettingsRepository repository;
    private final TenantConfigurationServiceV1 configuration;
    private final Secrets secrets;
    private final Audit audit;
    private final JsonMapper json;

    public MediaSettingsServiceV1(MediaSettingsRepository repository, TenantConfigurationServiceV1 configuration,
                                    Secrets secrets, Audit audit, JsonMapper json) {
        this.repository = repository;
        this.configuration = configuration;
        this.secrets = secrets;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ platform catalogue and entitlements

    public List<Type> catalog() { return repository.catalog(); }

    @Transactional
    public List<Type> saveType(TypeRequest r) {
        if (r.kind() == null) throw ApiException.bad("Choose scheduling behavior for this type.");
        var prior = catalog().stream().filter(t -> t.key().equals(r.key())).findFirst();
        if (prior.isPresent() && !prior.get().kind().equals(r.kind()))
            throw ApiException.bad("An existing type's scheduling behavior cannot change. Create another type.");
        repository.saveType(r.key(), r.label().trim(), r.kind(), r.enabled());
        audit.platform(Auth.userId(), "media-type.save", "media_type", null, r.key());
        return catalog();
    }

    public List<String> entitlements(UUID org) { return Times.codes(repository.entitlements(org)); }

    /** Platform view of a project's entitlements; partners and the platform org are not found. */
    public List<String> entitlementsForPlatform(UUID org) {
        requireCustomer(org);
        return entitlements(org);
    }

    public List<String> entitle(UUID org, Entitlements r) {
        requireCustomer(org);
        Set<String> known = new HashSet<>(catalog().stream().map(Type::key).toList());
        if (r.types().isEmpty() || !known.containsAll(r.types())) throw ApiException.bad("Choose supported media types.");
        repository.setEntitlements(org, String.join(",", r.types().stream().distinct().toList()));
        audit.platform(Auth.userId(), "media-types.entitle", "organization", org, String.join(",", r.types()));
        return entitlements(org);
    }

    private void requireCustomer(UUID org) {
        if (!repository.isCustomerOrganization(org)) throw ApiException.notFound("Organization");
    }

    // ------------------------------------------------------------------ project settings

    public Row row(UUID org) { return repository.settings(org).orElse(null); }

    public View view(UUID org) {
        List<String> entitled = entitlements(org);
        List<Type> types = catalog().stream().filter(t -> t.enabled() && entitled.contains(t.key())).toList();
        Row row = row(org);
        List<String> keys = types.stream().map(Type::key).toList();
        List<String> on = row == null ? keys : Times.codes(row.enabledTypes()).stream().filter(keys::contains).toList();
        String def = row != null && on.contains(row.defaultType()) ? row.defaultType()
                : on.contains("MOVIE") ? "MOVIE" : on.stream().findFirst().orElse("");
        return new View(types, on, def, premiereTime(org), days(weekdays(org)), personalizedAds(org),
                row != null && row.viewerSecret() != null, row == null ? 0 : row.revision());
    }

    /** Saves the Settings form. Optimistic: {@code expectedRevision} must match. */
    @Transactional
    public View save(Tenant t, SettingsRequest r) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        repository.lockProject(t.orgId());
        View current = view(t.orgId());
        if (current.revision() != r.expectedRevision()) throw ApiException.conflict("STALE", "Settings changed. Reload before saving.");
        List<String> known = current.types().stream().map(Type::key).toList();
        if (r.enabledTypes().isEmpty() || !known.containsAll(r.enabledTypes()) || !r.enabledTypes().contains(r.defaultType()))
            throw ApiException.bad("Enable supported media types and choose one as the default.");
        LocalTime premiere = time(r.premiereTime());
        String weekdays = daysCsv(r.weekdays());
        repository.saveSettings(t.orgId(), String.join(",", r.enabledTypes().stream().distinct().toList()), r.defaultType(),
                premiere, weekdays, r.personalizedAds());
        // Only changed values become project overrides, so untouched fields keep inheriting tenant/global defaults.
        configuration.writeProjectValueIfChanged(t.orgId(), TenantConfigurationServiceV1.PREMIERE_TIME, json.getNodeFactory().stringNode(premiere.toString()));
        configuration.writeProjectValueIfChanged(t.orgId(), TenantConfigurationServiceV1.WEEKDAYS, json.getNodeFactory().stringNode(weekdays));
        configuration.writeProjectValueIfChanged(t.orgId(), TenantConfigurationServiceV1.PERSONALIZED_ADS, json.getNodeFactory().booleanNode(r.personalizedAds()));
        audit.log(t, "settings.update", "organization", t.orgId(), "Media, programming and viewer advertising defaults");
        return view(t.orgId());
    }

    /** Replaces the viewer-context signing key; existing sessions lose personalization immediately. */
    @Transactional
    public View rotateViewerKey(Tenant t, ViewerKeyRequest request) {
        t.require(Tenant.Perm.TEAM_MANAGE);
        if (row(t.orgId()) == null) throw ApiException.bad("Save your settings before creating an integration key.");
        repository.setViewerSecret(t.orgId(), secrets.seal(request.key()));
        repository.forgetViewerContext(t.orgId());
        audit.log(t, "viewer-key.rotate", "organization", t.orgId(), "Previous viewer context tokens are revoked for future sessions");
        return view(t.orgId());
    }

    // ------------------------------------------------------------------ resolved dynamic properties

    // The hardcoded fallbacks only apply if a global default was removed from the database.
    public String premiereTime(UUID org) { return text(org, TenantConfigurationServiceV1.PREMIERE_TIME, "20:00"); }

    public String weekdays(UUID org) { return text(org, TenantConfigurationServiceV1.WEEKDAYS, "1,2,3,4,5,6,7"); }

    public boolean personalizedAds(UUID org) {
        var v = configuration.effective(org, TenantConfigurationServiceV1.PERSONALIZED_ADS);
        return v != null && v.asBoolean();
    }

    private String text(UUID org, String key, String fallback) {
        var v = configuration.effective(org, key);
        return v == null ? fallback : v.asString();
    }

    /** The media type if it is enabled for the project, otherwise 400. */
    public Type require(UUID org, String key) {
        View v = view(org);
        return v.types().stream().filter(t -> t.key().equals(key) && v.enabledTypes().contains(key)).findFirst()
                .orElseThrow(() -> ApiException.bad("This media type is not enabled for your company."));
    }

    // ------------------------------------------------------------------ validation helpers (also used by programming rules)

    /** Parses "1,3,5" into sorted unique weekdays 1 (Monday) … 7 (Sunday). */
    public static List<Integer> days(String csv) {
        try {
            List<Integer> days = Arrays.stream(csv.split(",")).map(String::trim).map(Integer::parseInt).distinct().sorted().toList();
            if (days.isEmpty() || days.stream().anyMatch(d -> d < 1 || d > 7)) throw new IllegalArgumentException();
            return days;
        } catch (Exception e) { throw ApiException.bad("Choose weekdays from 1 (Monday) to 7 (Sunday)."); }
    }

    public static String daysCsv(List<Integer> days) {
        if (days == null) throw ApiException.bad("Choose at least one weekday.");
        return String.join(",", days(days.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")).stream().map(String::valueOf).toList());
    }

    /** A wall-clock time on a five-second segment boundary. */
    public static LocalTime time(String time) {
        try {
            LocalTime t = LocalTime.parse(time);
            if (t.getNano() != 0 || t.getSecond() % Times.SEG_S != 0) throw new IllegalArgumentException();
            return t;
        } catch (Exception e) { throw ApiException.bad("Choose a valid time on a five-second boundary."); }
    }
}
