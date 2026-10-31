package com.faststudio.v1.services.programming;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.dto.programming.ProgrammingDto.GenerateRequest;
import com.faststudio.v1.repositories.programming.ProgrammingRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 10 minutes, extends each channel's draft with the next 14 days of smart-programming placements.
 * Drafts only: publishing remains an explicit operator action. A channel whose draft is being edited is skipped
 * (STALE) and retried on the next run.
 */
@Component
public class ProgrammingRefresh {
    private static final Logger log = LoggerFactory.getLogger(ProgrammingRefresh.class);
    private final Data data;
    private final ProgrammingRuleRepository repository;
    private final ProgrammingRuleServiceV1 rules;

    public ProgrammingRefresh(Data data, ProgrammingRuleRepository repository, ProgrammingRuleServiceV1 rules) {
        this.data = data;
        this.repository = repository;
        this.rules = rules;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void tick() {
        var rows = TenantScope.bypass(repository::channelsWithActiveRules);
        for (var row : rows) {
            try {
                // A system actor with channel-operator rights, scoped to that project.
                var actor = new Tenant(row.orgId(), row.slug(), row.name(), null, Tenant.Role.CHANNEL_OPERATOR, "CUSTOMER");
                TenantScope.as(row.orgId(), () -> rules.generate(actor, row.channelId(),
                        new GenerateRequest(data.draft(row.orgId(), row.channelId()).revision(), 14)));
            } catch (RuntimeException e) {
                log.debug("Draft refresh for {} deferred: {}", row.channelId(), e.getMessage());
            }
        }
    }
}
