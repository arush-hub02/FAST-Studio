package com.faststudio.v1.dto.team;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Platform administration of people across organizations (platform → People). */
public final class PeopleDto {
    private PeopleDto() {}

    /** One organization a person belongs to; customRoleKey narrows the built-in role when set. */
    /** viaTenant: from a tenant-wide role (changed on the tenant, not per organization). */
    public record Membership(UUID orgId, String slug, String name, String kind, String role, String customRoleKey, boolean viaTenant) {}

    /** A person and every organization they belong to. disabled: the account is switched off. */
    public record Person(UUID id, String email, String name, boolean platformAdmin, boolean disabled, OffsetDateTime createdAt,
                         List<Membership> memberships) {}

    /** A page of people; total counts every match, not only this page. */
    public record Page(List<Person> people, int total) {}

    /** Adds a person to an organization, creating the account when the email is new. */
    public record AddPerson(@NotBlank @Email String email, @NotBlank @Size(max = 80) String name, @NotNull UUID orgId,
                            @NotBlank String role, String customRoleKey) {}

    public record AddResult(Person person, String temporaryPassword, String note) {}

    public record MembershipRequest(@NotBlank String role, String customRoleKey) {}

    public record StatusRequest(boolean disabled) {}

    /** A custom role the platform can give in one organization: it narrows its built-in baseRole. */
    public record CustomRole(String key, String name, String baseRole) {}
}
