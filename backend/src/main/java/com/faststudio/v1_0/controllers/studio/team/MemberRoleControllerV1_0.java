package com.faststudio.v1_0.controllers.studio.team;

import com.faststudio.core.Tenant;
import com.faststudio.v1_0.services.tenancy.AccessRoleServiceV1_0;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Roles that can be assigned to members of the current organization (customer project or partner). */
@RestController
public class MemberRoleControllerV1_0 {
    private final AccessRoleServiceV1_0 service;

    public MemberRoleControllerV1_0(AccessRoleServiceV1_0 service) { this.service = service; }

    @GetMapping("/api/orgs/{org}/members/roles")
    List<AccessRoleServiceV1_0.Assignable> available(Tenant tenant) { return service.assignableIn(tenant); }
}
