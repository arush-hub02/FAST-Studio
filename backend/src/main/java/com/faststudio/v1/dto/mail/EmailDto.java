package com.faststudio.v1.dto.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Platform email administration: template drafts and previews, test sends, and the sent-email log. */
public final class EmailDto {
    private EmailDto() {}

    public record Draft(String subject, String body) {}

    /** A draft rendered with example values, plus problems such as unknown placeholders. */
    public record Preview(String subject, String text, String html, List<String> problems) {}

    public record TestRequest(@NotBlank @Email String to) {}

    /** One logged email; {@code canRetry} when it failed and its content is still stored. */
    public record Message(UUID id, UUID orgId, String orgName, String template, String toEmail, String subject, String status, String detail,
                          int attempts, OffsetDateTime createdAt, OffsetDateTime sentAt, boolean canRetry) {}

    /** The logged copy of an email: one-time passwords and links are hidden. */
    public record MessageBody(String subject, String text, String html) {}
}
