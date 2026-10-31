package com.faststudio.v1.controllers.studio.team;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.services.tenancy.AccessRoleServiceV1;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Roles that can be assigned to members of the current organization (customer project or partner). */
@RestController
public class MemberRoleControllerV1 {
    private final AccessRoleServiceV1 service;

    public MemberRoleControllerV1(AccessRoleServiceV1 service) { this.service = service; }

    @GetMapping("/api/orgs/{org}/members/roles")
    List<AccessRoleServiceV1.Assignable> available(Tenant tenant) { return service.assignableIn(tenant); }
}
