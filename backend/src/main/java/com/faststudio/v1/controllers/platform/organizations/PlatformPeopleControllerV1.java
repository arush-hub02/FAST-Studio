package com.faststudio.v1.controllers.platform.organizations;

import com.faststudio.v1.dto.team.PeopleDto.*;
import com.faststudio.v1.services.team.PlatformPeopleServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** People across every organization (WebConfig limits /api/platform/** to platform administrators). */
@RestController
@RequestMapping("/api/platform/people")
public class PlatformPeopleControllerV1 {
    private final PlatformPeopleServiceV1 people;

    public PlatformPeopleControllerV1(PlatformPeopleServiceV1 people) { this.people = people; }

    @GetMapping
    Page list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return people.list(q, limit, offset);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AddResult add(@Valid @RequestBody AddPerson r) { return people.add(r); }

    @GetMapping("/roles")
    java.util.List<CustomRole> roles(@RequestParam UUID orgId) { return people.customRoles(orgId); }

    @GetMapping("/{userId}")
    Person get(@PathVariable UUID userId) { return people.get(userId); }

    @PutMapping("/{userId}/memberships/{orgId}")
    Person setMembership(@PathVariable UUID userId, @PathVariable UUID orgId, @Valid @RequestBody MembershipRequest r) {
        return people.setMembership(userId, orgId, r);
    }

    @DeleteMapping("/{userId}/memberships/{orgId}")
    Person removeMembership(@PathVariable UUID userId, @PathVariable UUID orgId) { return people.removeMembership(userId, orgId); }

    @PutMapping("/{userId}/status")
    Person setStatus(@PathVariable UUID userId, @Valid @RequestBody StatusRequest r) { return people.setStatus(userId, r); }
}
