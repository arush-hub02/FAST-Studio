package com.faststudio.v1.repositories.media;

import com.faststudio.v1.dto.media.MediaSettingsDto.ProjectType;
import com.faststudio.v1.dto.media.MediaSettingsDto.Row;
import com.faststudio.v1.dto.media.MediaSettingsDto.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the media type catalogue, project entitlements and {@code tenant_settings} (RLS-scoped to the project). */
@Repository
public class MediaSettingsRepository {
    private final JdbcClient db;

    public MediaSettingsRepository(JdbcClient db) { this.db = db; }

    public List<Type> catalog() {
        return db.sql("select key, label, kind, enabled from media_type order by label").query(Type.class).list();
    }

    /** Insert or update a catalogue type; {@code kind} is only set on insert. */
    public void saveType(String key, String label, String kind, boolean enabled) {
        db.sql("insert into media_type(key, label, kind, enabled) values (:k, :l, :b, :e) on conflict (key) do update set label = :l, enabled = :e")
                .param("k", key).param("l", label).param("b", kind).param("e", enabled).update();
    }

    /** Comma-separated media type keys the platform entitled this project to. */
    public String entitlements(UUID org) {
        return db.sql("select media_entitlements from organization where id = :o").param("o", org).query(String.class).single();
    }

    public void setEntitlements(UUID org, String csv) {
        db.sql("update organization set media_entitlements = :t where id = :o").param("t", csv).param("o", org).update();
    }

    public boolean isCustomerOrganization(UUID org) {
        return db.sql("select count(*) from organization where id = :o and kind = 'CUSTOMER'").param("o", org).query(Integer.class).single() > 0;
    }

    public Optional<Row> settings(UUID org) {
        return db.sql("""
                select enabled_types, default_type, premiere_time, weekdays, personalized_ads, viewer_secret, revision
                from tenant_settings where org_id = :o""").param("o", org).query(Row.class).optional();
    }

    /** Locks the project row so concurrent saves are serialized with the revision check. */
    public void lockProject(UUID org) {
        db.sql("select id from organization where id = :o for update").param("o", org).query(UUID.class).single();
    }

    /** Upserts settings and bumps the revision. premiere/weekdays/personalized columns are a rollback mirror since V15. */
    public void saveSettings(UUID org, String enabledTypes, String defaultType, LocalTime premiereTime, String weekdays, boolean personalizedAds) {
        db.sql("""
                insert into tenant_settings(org_id, enabled_types, default_type, premiere_time, weekdays, personalized_ads)
                values (:o, :types, :def, :at, :days, :ads) on conflict (org_id) do update set enabled_types = :types,
                default_type = :def, premiere_time = :at, weekdays = :days, personalized_ads = :ads, revision = tenant_settings.revision + 1""")
                .param("o", org).param("types", enabledTypes).param("def", defaultType).param("at", premiereTime)
                .param("days", weekdays).param("ads", personalizedAds).update();
    }

    public void setViewerSecret(UUID org, String sealedSecret) {
        db.sql("update tenant_settings set viewer_secret = :s, revision = revision + 1 where org_id = :o")
                .param("s", sealedSecret).param("o", org).update();
    }

    /** Drops viewer identity from existing sessions so tokens signed with the old key stop personalizing. */
    public void forgetViewerContext(UUID org) {
        db.sql("update playback_session set viewer_key = null, audiences = '', personalized_until = null where org_id = :o")
                .param("o", org).update();
    }

    // ------------------------------------------------------------------ the project's own media types (P4)

    public List<ProjectType> projectTypes(UUID org) {
        return db.sql("""
                select t.key, t.label, t.kind, t.enabled,
                  (select count(*) from asset a where a.org_id = t.org_id and a.media_type = t.key) as assets
                from project_media_type t where t.org_id = :o order by t.label""").param("o", org).query(ProjectType.class).list();
    }

    /** Insert or update; {@code kind} is only set on insert. */
    public void saveProjectType(UUID org, String key, String label, String kind, boolean enabled) {
        db.sql("""
                insert into project_media_type(org_id, key, label, kind, enabled) values (:o, :k, :l, :b, :e)
                on conflict (org_id, key) do update set label = excluded.label, enabled = excluded.enabled""")
                .param("o", org).param("k", key).param("l", label).param("b", kind).param("e", enabled).update();
    }

    /** True when some project has its own type with this key (run as the system). */
    public boolean projectTypeKeyUsed(String key) {
        return db.sql("select count(*) from project_media_type /* all tenants: a new platform key must not collide with any project's */ where key = :k").param("k", key).query(Integer.class).single() > 0;
    }

    public int deleteProjectType(UUID org, String key) {
        return db.sql("delete from project_media_type where org_id = :o and key = :k").param("o", org).param("k", key).update();
    }
}
