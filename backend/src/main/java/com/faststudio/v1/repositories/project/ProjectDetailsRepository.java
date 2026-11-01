package com.faststudio.v1.repositories.project;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.dto.project.ProjectDetails;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ProjectDetailsRepository {
    private final JdbcClient db;

    public ProjectDetailsRepository(JdbcClient db) { this.db = db; }

    public record ProjectRow(UUID id, String slug, String name, String kind, String status, String features) {}
    public record LibraryState(String environment, boolean credentialsConfigured, boolean webhookConfigured) {}

    public ProjectRow project(UUID org) {
        return db.sql("select id, slug, name, kind, status, features from organization where id=:o and kind='CUSTOMER'")
                .param("o", org).query(ProjectRow.class).optional().orElseThrow(() -> ApiException.notFound("Project"));
    }

    /** Read credential presence in SQL: encrypted or decrypted values never enter the response projection. */
    public LibraryState library(UUID org) {
        return db.sql("""
                select environment, (token_id is not null and token_secret is not null) as credentials_configured,
                (webhook_secret is not null) as webhook_configured from media_connection where org_id=:o""")
                .param("o", org).query(LibraryState.class).optional().orElse(new LibraryState("", false, false));
    }

    public List<ProjectDetails.Advertising> advertising(UUID org) {
        return db.sql("""
                select id, name, kind, enabled, managed_by, (kind='DIRECT' or tag_url is not null) as configured
                from ad_source where org_id=:o order by position, id""")
                .param("o", org).query(ProjectDetails.Advertising.class).list();
    }

    public ProjectDetails.Summary summary(UUID org) {
        return db.sql("""
                select (select count(*) from asset where org_id=:o and status<>'ARCHIVED') as assets,
                (select count(*) from asset where org_id=:o and status='READY') as ready_assets,
                (select count(*) from channel where org_id=:o) as channels""")
                .param("o", org).query(ProjectDetails.Summary.class).single();
    }
}
