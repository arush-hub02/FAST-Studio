package com.faststudio.v1.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

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
}
