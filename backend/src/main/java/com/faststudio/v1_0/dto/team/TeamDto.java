package com.faststudio.v1_0.dto.team;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Requests and responses for organization members (Team screen) and platform organization administration. */
public final class TeamDto {
    private TeamDto() {}

    // ------------------------------------------------------------------ members of one organization

    /** A member; {@code customRoleKey} is set when a configurable role narrows the built-in {@code role}. */
    public record Member(UUID userId, String name, String email, String role, OffsetDateTime createdAt, String customRoleKey) {}

    public record InviteRequest(@NotBlank @Email String email, @NotBlank @Size(max = 80) String name,
                                @NotBlank String role, String customRoleKey) {}

    /** {@code temporaryPassword} is only set for a brand-new account and is shown once. */
    public record InviteResult(Member member, String temporaryPassword, String note) {}

    public record RoleRequest(@NotBlank String role, String customRoleKey) {}

    /** One-time password reset link (relative path) and the note to show the admin. */
    public record ResetLinkResult(String path, OffsetDateTime expiresAt, String note) {}

    // ------------------------------------------------------------------ platform administration of organizations

    /** Usage and health counts only — never customer content, schedules or finance. */
    public record OrgSummary(UUID id, String slug, String name, String status, String country, String timezone, String plan,
                             int maxChannels, long members, long channels, long liveChannels, long videos, long sessions,
                             OffsetDateTime createdAt, String kind, long partnerLinks) {}

    /** New organization with its first owner; kind CUSTOMER also creates its customer tenant (database trigger). */
    public record CreateOrg(@NotBlank @Size(max = 80) String name, @NotBlank @Pattern(regexp = "[a-z0-9-]{3,40}") String slug,
                            @NotBlank @Pattern(regexp = "[A-Z]{2}") String country, @NotBlank String timezone,
                            @NotBlank @Email String ownerEmail, @NotBlank String ownerName,
                            @Pattern(regexp = "CUSTOMER|PARTNER") String kind) {}

    public record PlatformReset(@NotBlank @Email String email) {}

    public record OrgStatus(@Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}
}
