package com.faststudio.catalog;

import com.faststudio.core.*;
import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;
import org.springframework.stereotype.Service;
import java.time.LocalTime;
import java.util.*;

/**
 * Media and programming settings of a project.
 *
 * <p>Media types (platform catalogue + project entitlement) and the viewer key stay in {@code tenant_settings}.
 * Premiere time, weekdays and personalized ads are dynamic properties since V15: they resolve
 * project override → tenant value → global default through {@link TenantConfigurationServiceV1_0#effective}.
 */
@Service
public class MediaSettings {
    private final Data data;
    private final TenantConfigurationServiceV1_0 configuration;
    public MediaSettings(Data data, TenantConfigurationServiceV1_0 configuration) { this.data = data; this.configuration = configuration; }
    public record Type(String key, String label, String kind, boolean enabled) {}
    public record Row(String enabledTypes, String defaultType, LocalTime premiereTime, String weekdays,
                      boolean personalizedAds, String viewerSecret, int revision) {}
    public record View(List<Type> types, List<String> enabledTypes, String defaultType, String premiereTime,
                       List<Integer> weekdays, boolean personalizedAds, boolean viewerKeySet, int revision) {}

    public List<Type> catalog() {
        return data.db.sql("select key, label, kind, enabled from media_type order by label").query(Type.class).list();
    }
    public List<String> entitlements(UUID org) {
        return Times.codes(data.db.sql("select media_entitlements from organization where id = :o").param("o", org).query(String.class).single());
    }
    public Row row(UUID org) {
        return data.db.sql("select enabled_types, default_type, premiere_time, weekdays, personalized_ads, viewer_secret, revision from tenant_settings where org_id = :o")
                .param("o", org).query(Row.class).optional().orElse(null);
    }
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

    // Resolved dynamic properties; the hardcoded fallbacks only apply if a global default was removed from the database.
    public String premiereTime(UUID org) { return text(org, TenantConfigurationServiceV1_0.PREMIERE_TIME, "20:00"); }
    public String weekdays(UUID org) { return text(org, TenantConfigurationServiceV1_0.WEEKDAYS, "1,2,3,4,5,6,7"); }
    public boolean personalizedAds(UUID org) {
        var v = configuration.effective(org, TenantConfigurationServiceV1_0.PERSONALIZED_ADS);
        return v != null && v.asBoolean();
    }
    private String text(UUID org, String key, String fallback) {
        var v = configuration.effective(org, key);
        return v == null ? fallback : v.asString();
    }
    public Type require(UUID org, String key) {
        View v = view(org);
        return v.types().stream().filter(t -> t.key().equals(key) && v.enabledTypes().contains(key)).findFirst()
                .orElseThrow(() -> ApiException.bad("This media type is not enabled for your company."));
    }
    public static List<Integer> days(String csv) {
        try {
            List<Integer> days = Arrays.stream(csv.split(",")).map(String::trim).map(Integer::parseInt).distinct().sorted().toList();
            if (days.isEmpty() || days.stream().anyMatch(d -> d < 1 || d > 7)) throw new IllegalArgumentException();
            return days;
        } catch (Exception e) { throw ApiException.bad("Choose weekdays from 1 (Monday) to 7 (Sunday)."); }
    }
    public static String daysCsv(List<Integer> days) {
        if (days == null) throw ApiException.bad("Choose at least one weekday.");
        return String.join(",", days(days.stream().map(String::valueOf).reduce((a,b) -> a + "," + b).orElse("")).stream().map(String::valueOf).toList());
    }
    public static LocalTime time(String time) {
        try {
            LocalTime t = LocalTime.parse(time);
            if (t.getNano() != 0 || t.getSecond() % Times.SEG_S != 0) throw new IllegalArgumentException();
            return t;
        } catch (Exception e) { throw ApiException.bad("Choose a valid time on a five-second boundary."); }
    }
}
