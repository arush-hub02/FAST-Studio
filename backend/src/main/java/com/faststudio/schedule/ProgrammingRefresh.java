package com.faststudio.schedule;

import com.faststudio.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

/** Extend drafts only; publishing remains an explicit operator action. */
@Component
public class ProgrammingRefresh {
    private static final Logger log=LoggerFactory.getLogger(ProgrammingRefresh.class);
    private final Data data;
    private final ProgrammingController controller;
    public ProgrammingRefresh(Data data,ProgrammingController controller) { this.data=data; this.controller=controller; }
    record Channel(UUID orgId,String slug,String name,UUID channelId) {}
    @Scheduled(initialDelay=60_000,fixedDelay=600_000)
    public void tick() {
        var rows=TenantScope.bypass(()->data.db.sql("""
            select distinct o.id as org_id,o.slug,o.name,c.id as channel_id from programming_rule r
            join channel c on c.org_id=r.org_id and c.id=r.channel_id join organization o on o.id=c.org_id
            where r.enabled and r.end_date>=current_date and c.status<>'ARCHIVED' and o.status='ACTIVE'""").query(Channel.class).list());
        for(var row:rows) try {
            TenantScope.as(row.orgId(),()->controller.generate(new Tenant(row.orgId(),row.slug(),row.name(),null,Tenant.Role.CHANNEL_OPERATOR,"CUSTOMER"),
                    row.channelId(),new ProgrammingController.GenerateRequest(data.draft(row.orgId(),row.channelId()).revision(),14)));
        } catch(RuntimeException e) { log.debug("Draft refresh for {} deferred: {}",row.channelId(),e.getMessage()); }
    }
}
