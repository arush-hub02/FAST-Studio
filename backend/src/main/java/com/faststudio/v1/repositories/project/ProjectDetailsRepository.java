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
                select coalesce(max(v.value) filter (where v.property_key = 'environment'), '') as environment,
                (count(*) filter (where v.property_key in ('token_id', 'token_secret')) = 2) as credentials_configured,
                (count(*) filter (where v.property_key = 'webhook_secret') = 1) as webhook_configured
                from connector_instance i left join connector_value v on v.instance_id = i.id and v.org_id = :o
                where i.org_id = :o and i.connector_key = 'mux' and i.enabled""")
                .param("o", org).query(LibraryState.class).single();
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
