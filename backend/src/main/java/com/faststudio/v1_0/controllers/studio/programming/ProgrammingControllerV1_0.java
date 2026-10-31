package com.faststudio.v1_0.controllers.studio.programming;

import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.dto.programming.ProgrammingDto.*;
import com.faststudio.v1_0.services.programming.ProgrammingRuleServiceV1_0;
import com.faststudio.v1_0.services.programming.ProgrammingServiceV1_0;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Smart programming rules of a channel. Rules live in {@link ProgrammingRuleServiceV1_0}. */
@RestController
@RequestMapping("/api/orgs/{org}/channels/{id}/programming")
public class ProgrammingControllerV1_0 {
    private final ProgrammingRuleServiceV1_0 rules;

    public ProgrammingControllerV1_0(ProgrammingRuleServiceV1_0 rules) { this.rules = rules; }

    @GetMapping
    List<ProgrammingServiceV1_0.Rule> list(Tenant t, @PathVariable UUID id) { return rules.list(t, id); }

    @PostMapping("/preview")
    ProgrammingServiceV1_0.Preview preview(Tenant t, @PathVariable UUID id, @Valid @RequestBody RuleRequest r) { return rules.preview(t, id, r); }

    @PostMapping
    Map<String, UUID> create(Tenant t, @PathVariable UUID id, @Valid @RequestBody RuleRequest r) { return rules.create(t, id, r); }

    @PutMapping("/{ruleId}")
    Map<String, Boolean> status(Tenant t, @PathVariable UUID id, @PathVariable UUID ruleId, @RequestBody StatusRequest r) {
        return rules.setEnabled(t, id, ruleId, r);
    }

    @PostMapping("/generate")
    ProgrammingServiceV1_0.Preview generate(Tenant t, @PathVariable UUID id, @Valid @RequestBody GenerateRequest r) { return rules.generate(t, id, r); }
}
