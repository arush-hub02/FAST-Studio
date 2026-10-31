package com.faststudio.v1.repositories.channels;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** SQL for channels (RLS-scoped to the project; queries also filter by org). Reading channels goes through {@code Data}. */
@Repository
public class ChannelRepository {
    private final JdbcClient db;

    public ChannelRepository(JdbcClient db) { this.db = db; }

    /** Channels whose slug starts with the given one (to make a new slug unique). */
    public int slugsStartingWith(UUID org, String slug) {
        return db.sql("select count(*) from channel where org_id = :o and slug like :s").param("o", org).param("s", slug + "%").query(Integer.class).single();
    }

    public record Fields(String name, String description, String genre, String language, String timezone, String color,
                         String territories, int adSecondsPerHour, int breakSeconds, UUID fallbackAssetId) {}

    public UUID insert(UUID org, String slug, Fields f) {
        return db.sql("""
                insert into channel(org_id, slug, name, description, genre, language, timezone, color, territories, ad_seconds_per_hour, break_seconds, fallback_asset_id)
                values (:o, :slug, :n, :d, :g, :l, :tz, :c, :terr, :ads, :brk, :fb) returning id""")
                .param("o", org).param("slug", slug).param("n", f.name()).param("d", f.description()).param("g", f.genre())
                .param("l", f.language()).param("tz", f.timezone()).param("c", f.color()).param("terr", f.territories())
                .param("ads", f.adSecondsPerHour()).param("brk", f.breakSeconds()).param("fb", f.fallbackAssetId()).query(UUID.class).single();
    }

    public void update(UUID org, UUID id, Fields f) {
        db.sql("""
                update channel set name = :n, description = :d, genre = :g, language = :l, timezone = :tz, color = :c, territories = :terr,
                  ad_seconds_per_hour = :ads, break_seconds = :brk, fallback_asset_id = :fb, updated_at = now() where org_id = :o and id = :id""")
                .param("n", f.name()).param("d", f.description()).param("g", f.genre()).param("l", f.language()).param("tz", f.timezone())
                .param("c", f.color()).param("terr", f.territories()).param("ads", f.adSecondsPerHour()).param("brk", f.breakSeconds())
                .param("fb", f.fallbackAssetId()).param("o", org).param("id", id).update();
    }

    public void setStatus(UUID org, UUID id, String status) {
        db.sql("update channel set status = :s, updated_at = now() where org_id = :o and id = :id").param("s", status).param("o", org).param("id", id).update();
    }
}
