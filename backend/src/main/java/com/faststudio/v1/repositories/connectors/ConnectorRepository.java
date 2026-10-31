package com.faststudio.v1.repositories.connectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL behind the platform's per-project Connectors page. */
@Repository
public class ConnectorRepository {
    private final JdbcClient db;

    public ConnectorRepository(JdbcClient db) { this.db = db; }

    public boolean isPlatformAdmin(UUID userId) {
        return db.sql("select platform_admin from app_user where id = :id").param("id", userId).query(Boolean.class).single();
    }

    public record Org(UUID id, String name, String kind, String features) {}

    public Optional<Org> organization(UUID org) {
        return db.sql("select id, name, kind, features from organization where id = :id").param("id", org).query(Org.class).optional();
    }

    /** Media keys of the project's ready videos (caller runs as that project). */
    public List<String> readyMediaKeys(UUID org) {
        return db.sql("select media_key from asset where org_id = :o and status = 'READY' and media_key is not null")
                .param("o", org).query(String.class).list();
    }

    public void setFeatures(UUID org, String csv) {
        db.sql("update organization set features = :f where id = :id").param("f", csv).param("id", org).update();
    }
}
