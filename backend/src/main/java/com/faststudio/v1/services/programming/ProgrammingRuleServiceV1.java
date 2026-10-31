package com.faststudio.v1.services.programming;

import com.faststudio.v1.dto.programming.ProgrammingDto.Rule;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.programming.ProgrammingDto.*;
import com.faststudio.v1.repositories.programming.ProgrammingRuleRepository;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Smart programming rules of a channel: validate, preview, save, pause/resume, and generate placements into the
 * draft schedule. Generation never publishes; publishing stays an explicit operator action.
 * Placement planning itself is in {@link ProgrammingServiceV1}.
 */
@Service
public class ProgrammingRuleServiceV1 {
    private final Data data;
    private final ProgrammingServiceV1 programming;
    private final ProgrammingRuleRepository repository;
    private final Audit audit;

    public ProgrammingRuleServiceV1(Data data, ProgrammingServiceV1 programming, ProgrammingRuleRepository repository, Audit audit) {
        this.data = data;
        this.programming = programming;
        this.repository = repository;
        this.audit = audit;
    }

    public List<Rule> list(Tenant t, UUID channel) {
        data.channel(t.orgId(), channel);
        return programming.rules(t.orgId(), channel);
    }

    /** Validates a request into a rule: future range of at most a year, repeat after premiere, series has episodes. */
    private Rule rule(Tenant t, UUID channel, RuleRequest r) {
        Data.Channel ch = data.channel(t.orgId(), channel);
        if (r.startDate().isBefore(LocalDate.now(ZoneId.of(ch.timezone()))) || r.endDate().isBefore(r.startDate()) || r.endDate().isAfter(r.startDate().plusDays(366)))
            throw ApiException.bad("Choose a future date range of at most one year.");
        LocalTime at = MediaSettingsServiceV1.time(r.premiereTime());
        LocalTime repeat = r.repeatTime() == null || r.repeatTime().isBlank() ? null : MediaSettingsServiceV1.time(r.repeatTime());
        if (repeat != null && !repeat.isAfter(at)) throw ApiException.bad("The same-day repeat must start after the premiere.");
        boolean hasEpisodes = data.assets(t.orgId()).values().stream()
                .anyMatch(a -> "EPISODE".equals(a.kind()) && a.series() != null && a.series().trim().equalsIgnoreCase(r.series().trim()));
        if (!hasEpisodes) throw ApiException.bad("Add episodes for this series to the Library first.");
        return new Rule(null, t.orgId(), channel, r.series().trim(), r.startDate(), r.endDate(), at, repeat,
                MediaSettingsServiceV1.daysCsv(r.weekdays()), r.startSeason(), r.startEpisode(), r.loop(), true);
    }

    /** What the rule would place over its first 30 days, without saving anything. */
    public ProgrammingServiceV1.Preview preview(Tenant t, UUID channel, RuleRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        var rule = rule(t, channel, r);
        return programming.preview(t.orgId(), channel, List.of(rule), r.startDate().plusDays(30));
    }

    @Transactional
    public Map<String, UUID> create(Tenant t, UUID channel, RuleRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        var rule = rule(t, channel, r);
        UUID id = repository.insertRule(t.orgId(), channel, rule);
        audit.log(t, "programming.create", "channel", channel, rule.series());
        return Map.of("id", id);
    }

    /** Pausing keeps placements already generated into the draft. */
    public Map<String, Boolean> setEnabled(Tenant t, UUID channel, UUID rule, StatusRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        data.channel(t.orgId(), channel);
        if (!repository.setEnabled(t.orgId(), channel, rule, r.enabled())) throw ApiException.notFound("Programming rule");
        audit.log(t, "programming.status", "channel", channel, r.enabled() ? "Enabled" : "Paused; existing placements retained");
        return Map.of("enabled", r.enabled());
    }

    /**
     * Adds the next {@code days} of placements of every rule to the channel's draft schedule. Serialized per channel
     * and guarded by the draft revision, so concurrent edits fail with STALE instead of interleaving.
     */
    @Transactional
    public ProgrammingServiceV1.Preview generate(Tenant t, UUID channel, GenerateRequest r) {
        t.require(Tenant.Perm.SCHEDULE_EDIT);
        repository.lockChannel(t.orgId(), channel).orElseThrow(() -> ApiException.notFound("Channel"));
        var draft = data.draft(t.orgId(), channel);
        if (draft.revision() != r.expectedRevision()) throw ApiException.conflict("STALE", "Schedule changed. Reload before generating.");
        LocalDate through = LocalDate.now(ZoneId.of(data.channel(t.orgId(), channel).timezone())).plusDays(r.days() - 1);
        var preview = programming.preview(t.orgId(), channel, programming.rules(t.orgId(), channel), through);
        if (preview.placements().isEmpty()) return preview;
        if (!repository.bumpDraftRevision(t.orgId(), draft.id(), r.expectedRevision()))
            throw ApiException.conflict("STALE", "Schedule changed. Reload before generating.");
        for (var p : preview.placements()) {
            if (!p.encore()) repository.insertOccurrence(t.orgId(), p.ruleId(), p.date(), p.assetId());
            for (var i : p.items())
                repository.insertDraftEntry(t.orgId(), draft.id(), i.kind(), i.assetId(), i.offsetMs(), i.start(), i.end(), i.ownership(), p.ruleId(), p.date());
        }
        audit.log(t, "programming.generate", "channel", channel, preview.placements().size() + " placements added to draft");
        return new ProgrammingServiceV1.Preview(draft.revision() + 1, preview.placements(), preview.notices());
    }
}
