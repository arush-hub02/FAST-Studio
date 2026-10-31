package com.faststudio.v1.controllers.studio.team;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.team.TeamDto.*;
import com.faststudio.v1.services.team.TeamServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Members of the current organization (Team screen). Rules live in {@link TeamServiceV1}. */
@RestController
@RequestMapping("/api/orgs/{org}/members")
public class TeamControllerV1 {
    private final TeamServiceV1 team;

    public TeamControllerV1(TeamServiceV1 team) { this.team = team; }

    @GetMapping
    List<Member> members(Tenant t) { return team.members(t); }

    @PostMapping
    InviteResult invite(Tenant t, @Valid @RequestBody InviteRequest r) { return team.invite(t, r); }

    /** One-time password reset link for a member who belongs only to this organization. */
    @PostMapping("/{userId}/password-reset")
    ResetLinkResult resetMember(Tenant t, @PathVariable UUID userId) { return team.resetPassword(t, userId); }

    @PatchMapping("/{userId}")
    Map<String, String> changeRole(Tenant t, @PathVariable UUID userId, @Valid @RequestBody RoleRequest r) { return team.changeRole(t, userId, r); }

    @DeleteMapping("/{userId}")
    void remove(Tenant t, @PathVariable UUID userId) { team.remove(t, userId); }
}
