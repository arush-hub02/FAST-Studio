package com.faststudio.v1_0.controllers.studio.programming;

import com.faststudio.v1_0.core.common.ApiException;
import com.faststudio.v1_0.core.common.Audit;
import com.faststudio.v1_0.core.common.Data;
import com.faststudio.v1_0.core.common.Times;
import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.services.media.MediaSettingsServiceV1_0;
import com.faststudio.v1_0.services.programming.ProgrammingServiceV1_0;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/orgs/{org}/channels/{id}/programming")
public class ProgrammingControllerV1_0 {
    private final Data data;
    private final ProgrammingServiceV1_0 programming;
    private final Audit audit;
    public ProgrammingControllerV1_0(Data data, ProgrammingServiceV1_0 programming, Audit audit) { this.data = data; this.programming = programming; this.audit = audit; }
    @GetMapping
    List<ProgrammingServiceV1_0.Rule> list(Tenant t, @PathVariable UUID id) { data.channel(t.orgId(), id); return programming.rules(t.orgId(), id); }
    public record RuleRequest(@NotBlank @Size(max=120) String series, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                              @NotBlank String premiereTime, String repeatTime, @NotNull List<Integer> weekdays,
                              @Min(1) int startSeason, @Min(1) int startEpisode, boolean loop) {}
    private ProgrammingServiceV1_0.Rule rule(Tenant t, UUID channel, RuleRequest r) {
        Data.Channel ch = data.channel(t.orgId(), channel);
        if (r.startDate().isBefore(LocalDate.now(ZoneId.of(ch.timezone()))) || r.endDate().isBefore(r.startDate()) || r.endDate().isAfter(r.startDate().plusDays(366)))
            throw ApiException.bad("Choose a future date range of at most one year.");
        LocalTime at = MediaSettingsServiceV1_0.time(r.premiereTime()), repeat = r.repeatTime() == null || r.repeatTime().isBlank() ? null : MediaSettingsServiceV1_0.time(r.repeatTime());
        if (repeat != null && !repeat.isAfter(at)) throw ApiException.bad("The same-day repeat must start after the premiere.");
        if (data.assets(t.orgId()).values().stream().noneMatch(a -> "EPISODE".equals(a.kind()) && a.series() != null && a.series().trim().equalsIgnoreCase(r.series().trim())))
            throw ApiException.bad("Add episodes for this series to the Library first.");
        return new ProgrammingServiceV1_0.Rule(null,t.orgId(),channel,r.series().trim(),r.startDate(),r.endDate(),at,repeat,
                MediaSettingsServiceV1_0.daysCsv(r.weekdays()),r.startSeason(),r.startEpisode(),r.loop(),true);
    }
    @PostMapping("/preview")
    ProgrammingServiceV1_0.Preview preview(Tenant t, @PathVariable UUID id, @Valid @RequestBody RuleRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        var rule = rule(t,id,r);
        return programming.preview(t.orgId(),id,List.of(rule),r.startDate().plusDays(30));
    }
    @PostMapping
    @Transactional
    Map<String,UUID> create(Tenant t, @PathVariable UUID id, @Valid @RequestBody RuleRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        var rule = rule(t,id,r);
        UUID key = data.db.sql("""
            insert into programming_rule(org_id,channel_id,series,start_date,end_date,premiere_time,repeat_time,weekdays,start_season,start_episode,loop)
            values (:o,:c,:s,:from,:to,:at,:rep,:days,:se,:ep,:loop) returning id""")
                .param("o",t.orgId()).param("c",id).param("s",rule.series()).param("from",rule.startDate()).param("to",rule.endDate())
                .param("at",rule.premiereTime()).param("rep",rule.repeatTime()).param("days",rule.weekdays()).param("se",rule.startSeason()).param("ep",rule.startEpisode()).param("loop",rule.loop()).query(UUID.class).single();
        audit.log(t,"programming.create","channel",id,rule.series());
        return Map.of("id",key);
    }
    public record StatusRequest(boolean enabled) {}
    @PutMapping("/{ruleId}")
    Map<String,Boolean> status(Tenant t, @PathVariable UUID id, @PathVariable UUID ruleId, @RequestBody StatusRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT); data.channel(t.orgId(),id);
        if (data.db.sql("update programming_rule set enabled=:e where org_id=:o and channel_id=:c and id=:r")
                .param("e",r.enabled()).param("o",t.orgId()).param("c",id).param("r",ruleId).update()==0) throw ApiException.notFound("Programming rule");
        audit.log(t,"programming.status","channel",id,r.enabled()?"Enabled":"Paused; existing placements retained");
        return Map.of("enabled",r.enabled());
    }
    public record GenerateRequest(int expectedRevision, @Min(1) @Max(31) int days) {}
    @PostMapping("/generate")
    @Transactional
    public ProgrammingServiceV1_0.Preview generate(Tenant t, @PathVariable UUID id, @Valid @RequestBody GenerateRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        data.db.sql("select id from channel where org_id=:o and id=:c for update").param("o",t.orgId()).param("c",id).query(UUID.class).optional().orElseThrow(()->ApiException.notFound("Channel"));
        var draft = data.draft(t.orgId(),id);
        if(draft.revision()!=r.expectedRevision()) throw ApiException.conflict("STALE","Schedule changed. Reload before generating.");
        LocalDate through=LocalDate.now(ZoneId.of(data.channel(t.orgId(),id).timezone())).plusDays(r.days()-1);
        var preview=programming.preview(t.orgId(),id,programming.rules(t.orgId(),id),through);
        if(preview.placements().isEmpty()) return preview;
        if(data.db.sql("update schedule_version set revision=revision+1 where org_id=:o and id=:v and revision=:r")
                .param("o",t.orgId()).param("v",draft.id()).param("r",r.expectedRevision()).update()!=1) throw ApiException.conflict("STALE","Schedule changed. Reload before generating.");
        for(var p:preview.placements()) {
            if(!p.encore()) data.db.sql("insert into programming_occurrence(org_id,rule_id,air_date,asset_id) values (:o,:r,:d,:a)")
                    .param("o",t.orgId()).param("r",p.ruleId()).param("d",p.date()).param("a",p.assetId()).update();
            for(var i:p.items()) data.db.sql("""
                insert into schedule_entry(org_id,version_id,kind,asset_id,asset_offset_ms,start_at,end_at,ownership,rule_id,rule_date)
                values (:o,:v,:k,:a,:off,:s,:e,:own,:r,:d)""")
                    .param("o",t.orgId()).param("v",draft.id()).param("k",i.kind()).param("a",i.assetId()).param("off",i.offsetMs())
                    .param("s",Times.utc(i.start())).param("e",Times.utc(i.end())).param("own",i.ownership()).param("r",p.ruleId()).param("d",p.date()).update();
        }
        audit.log(t,"programming.generate","channel",id,preview.placements().size()+" placements added to draft");
        return new ProgrammingServiceV1_0.Preview(draft.revision()+1,preview.placements(),preview.notices());
    }
}
