package com.faststudio.v1.controllers.platform.email;

import com.faststudio.v1.dto.mail.EmailDto.*;
import com.faststudio.v1.services.mail.EmailAdminServiceV1;
import com.faststudio.v1.services.mail.EmailTemplates;
import com.faststudio.v1.services.mail.MailServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform administration of email (WebConfig: platform administrators only). Rules live in {@link EmailAdminServiceV1}. */
@RestController
@RequestMapping("/api/platform/email")
public class EmailControllerV1 {
    private final EmailAdminServiceV1 email;

    public EmailControllerV1(EmailAdminServiceV1 email) { this.email = email; }

    @GetMapping("/status")
    MailServiceV1.Status status() { return email.status(); }

    @GetMapping("/templates")
    List<EmailTemplates.View> list() { return email.templates(); }

    @PostMapping("/templates/{key}/preview")
    Preview preview(@PathVariable String key, @RequestBody Draft d) { return email.preview(key, d); }

    @PutMapping("/templates/{key}")
    List<EmailTemplates.View> save(@PathVariable String key, @RequestBody Draft d) { return email.save(key, d); }

    @DeleteMapping("/templates/{key}")
    List<EmailTemplates.View> reset(@PathVariable String key) { return email.reset(key); }

    @PostMapping("/test")
    Map<String, Object> test(@Valid @RequestBody TestRequest r) { return email.sendTest(r); }

    @GetMapping("/messages")
    List<Message> messages(@RequestParam(defaultValue = "200") int limit) { return email.messages(limit); }

    @GetMapping("/messages/{id}")
    MessageBody message(@PathVariable UUID id) { return email.message(id); }

    @PostMapping("/messages/{id}/retry")
    Map<String, Boolean> retry(@PathVariable UUID id) { return email.retry(id); }
}
