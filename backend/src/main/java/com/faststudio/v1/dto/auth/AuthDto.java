package com.faststudio.v1.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Sign-in, the signed-in person ("me") and password flows. */
public final class AuthDto {
    private AuthDto() {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    /**
     * One organization the person belongs to. {@code permissions} include custom-role reductions; {@code status} is
     * SUSPENDED when either the project or its customer tenant is suspended; tenant fields are null for partners.
     */
    public record Membership(UUID orgId, String slug, String name, String status, String role, List<String> permissions, String kind,
                             List<String> features, UUID tenantId, String tenantName) {}

    public record Me(UUID id, String name, String email, boolean platformAdmin, List<Membership> memberships, boolean demoMode,
                     boolean mustChangePassword) {}

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    /** Completes a reset link (single use, 24 h). */
    public record ResetRequest(@NotBlank String token, @NotBlank String newPassword) {}

    public record ForgotRequest(@NotBlank @Email String email) {}

    /** Seeded fictional account shown on the demo sign-in page (demo mode only). */
    public record DemoAccount(String email, String name, String role, String description) {}

    /**
     * A personal access token as listed to its owner (never the token itself). expiresAt null = never expires; org fields
     * null and roleKey PLATFORM_ADMIN = platform-administration scope.
     */
    public record PersonalToken(UUID id, String name, String tokenHint, UUID orgId, String orgSlug, String orgName, String roleKey,
                                OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime lastUsedAt) {}

    /**
     * New personal access token. org = an organization you belong to (id or slug), or "platform" (platform administrators,
     * role PLATFORM_ADMIN). role = the most it may do: a role at or below yours there, e.g. ANALYST for read-only.
     * expiresInDays null = never expires; otherwise 1 to 3650 days.
     */
    public record CreatePersonalToken(@NotBlank @Size(max = 80) String name, @NotBlank String org, @NotBlank String role,
                                      @Min(1) @Max(3650) Integer expiresInDays) {}

    /** The only time the token is shown: store it now (for example in the MCP client's configuration). */
    public record CreatedPersonalToken(String token, PersonalToken details) {}
}
