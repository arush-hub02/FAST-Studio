package com.faststudio.v1.controllers.studio.configuration;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.mail.EmailDto.Draft;
import com.faststudio.v1.dto.mail.EmailDto.Preview;
import com.faststudio.v1.services.mail.EmailTemplates;
import com.faststudio.v1.services.mail.ProjectEmailServiceV1;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A project's own wording of the emails sent about it, with fallback to the platform's (P5). */
@RestController
@RequestMapping("/api/orgs/{org}/email-templates")
public class ProjectEmailControllerV1 {
    private final ProjectEmailServiceV1 emails;

    public ProjectEmailControllerV1(ProjectEmailServiceV1 emails) { this.emails = emails; }

    @GetMapping
    List<EmailTemplates.View> list(Tenant t) { return emails.list(t); }

    @PostMapping("/{key}/preview")
    Preview preview(Tenant t, @PathVariable String key, @RequestBody Draft d) { return emails.preview(t, key, d); }

    @PutMapping("/{key}")
    List<EmailTemplates.View> save(Tenant t, @PathVariable String key, @RequestBody Draft d) { return emails.save(t, key, d); }

    @DeleteMapping("/{key}")
    List<EmailTemplates.View> reset(Tenant t, @PathVariable String key) { return emails.reset(t, key); }
}
