package com.faststudio.v1_0.controllers.platform.project;

import com.faststudio.v1_0.dto.project.ProjectDetails;
import com.faststudio.v1_0.services.project.ProjectDetailsServiceV1_0;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Project bootstrap as seen by a platform administrator (WebConfig guards /api/platform/**). */
@RestController
public class PlatformProjectDetailsControllerV1_0 {
    private final ProjectDetailsServiceV1_0 service;

    public PlatformProjectDetailsControllerV1_0(ProjectDetailsServiceV1_0 service) { this.service = service; }

    @GetMapping("/api/platform/orgs/{orgId}/project-details")
    public ResponseEntity<ProjectDetails> platform(@PathVariable UUID orgId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Cookie", "Authorization").body(service.platform(orgId));
    }
}
