package com.faststudio.v1.controllers.studio.project;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.project.ProjectDetails;
import com.faststudio.v1.services.project.ProjectDetailsServiceV1;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Project bootstrap for signed-in project members. Never cached: it reflects current permissions. */
@RestController
public class ProjectDetailsControllerV1 {
    private final ProjectDetailsServiceV1 service;

    public ProjectDetailsControllerV1(ProjectDetailsServiceV1 service) { this.service = service; }

    @GetMapping("/api/orgs/{org}/project-details")
    public ResponseEntity<ProjectDetails> tenant(Tenant tenant) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Cookie", "Authorization").body(service.tenant(tenant));
    }
}
