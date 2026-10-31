package com.faststudio.v1_0.controllers.platform.organizations;

import com.faststudio.v1_0.dto.team.TeamDto.*;
import com.faststudio.v1_0.services.team.OrganizationAdminServiceV1_0;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform administration of customer and partner organizations (WebConfig limits /api/platform/** to admins). */
@RestController
@RequestMapping("/api/platform")
public class OrganizationAdminControllerV1_0 {
    private final OrganizationAdminServiceV1_0 organizations;

    public OrganizationAdminControllerV1_0(OrganizationAdminServiceV1_0 organizations) { this.organizations = organizations; }

    /** Usage and health counts only — no customer content, schedules or finance. */
    @GetMapping("/orgs")
    List<OrgSummary> orgs() { return organizations.organizations(); }

    @PostMapping("/orgs")
    Map<String, Object> createOrg(@Valid @RequestBody CreateOrg r) { return organizations.create(r); }

    @PostMapping("/orgs/{id}/status")
    Map<String, String> orgStatus(@PathVariable UUID id, @Valid @RequestBody OrgStatus r) { return organizations.setStatus(id, r); }

    @PostMapping("/password-reset")
    ResetLinkResult platformReset(@Valid @RequestBody PlatformReset r) { return organizations.resetPassword(r); }
}
