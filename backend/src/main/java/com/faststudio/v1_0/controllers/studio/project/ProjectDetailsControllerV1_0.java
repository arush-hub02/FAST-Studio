package com.faststudio.v1_0.controllers.studio.project;

import com.faststudio.core.Tenant;
import com.faststudio.v1_0.dto.project.ProjectDetails;
import com.faststudio.v1_0.services.project.ProjectDetailsServiceV1_0;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Project bootstrap for signed-in project members. Never cached: it reflects current permissions. */
@RestController
public class ProjectDetailsControllerV1_0 {
    private final ProjectDetailsServiceV1_0 service;

    public ProjectDetailsControllerV1_0(ProjectDetailsServiceV1_0 service) { this.service = service; }

    @GetMapping("/api/orgs/{org}/project-details")
    public ResponseEntity<ProjectDetails> tenant(Tenant tenant) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Cookie", "Authorization").body(service.tenant(tenant));
    }
}
