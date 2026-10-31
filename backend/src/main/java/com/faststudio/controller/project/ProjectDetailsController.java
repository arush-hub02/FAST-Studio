package com.faststudio.controller.project;

import com.faststudio.core.Tenant;
import com.faststudio.dto.project.ProjectDetails;
import com.faststudio.service.project.ProjectDetailsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ProjectDetailsController {
    private final ProjectDetailsService service;
    public ProjectDetailsController(ProjectDetailsService service) { this.service = service; }

    @GetMapping("/api/orgs/{org}/project-details")
    public ResponseEntity<ProjectDetails> tenant(Tenant tenant) { return response(service.tenant(tenant)); }

    @GetMapping("/api/platform/orgs/{orgId}/project-details")
    public ResponseEntity<ProjectDetails> platform(@PathVariable UUID orgId) { return response(service.platform(orgId)); }

    private ResponseEntity<ProjectDetails> response(ProjectDetails body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy("Cookie", "Authorization").body(body);
    }
}
