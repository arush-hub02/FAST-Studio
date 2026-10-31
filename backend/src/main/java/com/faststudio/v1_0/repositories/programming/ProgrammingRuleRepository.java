package com.faststudio.v1_0.repositories.programming;

import com.faststudio.v1_0.core.common.Times;
import com.faststudio.v1_0.services.programming.ProgrammingServiceV1_0;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for programming rules and the draft placements they generate (RLS-scoped to the project). */
@Repository
public class ProgrammingRuleRepository {
    private final JdbcClient db;

    public ProgrammingRuleRepository(JdbcClient db) { this.db = db; }

    public UUID insertRule(UUID org, UUID channel, ProgrammingServiceV1_0.Rule r) {
        return db.sql("""
                insert into programming_rule(org_id, channel_id, series, start_date, end_date, premiere_time, repeat_time, weekdays, start_season, start_episode, loop)
                values (:o, :c, :s, :from, :to, :at, :rep, :days, :se, :ep, :loop) returning id""")
                .param("o", org).param("c", channel).param("s", r.series()).param("from", r.startDate()).param("to", r.endDate())
                .param("at", r.premiereTime()).param("rep", r.repeatTime()).param("days", r.weekdays()).param("se", r.startSeason())
                .param("ep", r.startEpisode()).param("loop", r.loop()).query(UUID.class).single();
    }

    /** False when the rule does not exist on that channel. */
    public boolean setEnabled(UUID org, UUID channel, UUID rule, boolean enabled) {
        return db.sql("update programming_rule set enabled = :e where org_id = :o and channel_id = :c and id = :r")
                .param("e", enabled).param("o", org).param("c", channel).param("r", rule).update() > 0;
    }

    /** Locks the channel row so draft generation and manual edits are serialized. */
    public Optional<UUID> lockChannel(UUID org, UUID channel) {
        return db.sql("select id from channel where org_id = :o and id = :c for update").param("o", org).param("c", channel).query(UUID.class).optional();
    }

    /** Optimistic bump of the draft revision; false when someone else changed it. */
    public boolean bumpDraftRevision(UUID org, UUID version, int expectedRevision) {
        return db.sql("update schedule_version set revision = revision + 1 where org_id = :o and id = :v and revision = :r")
                .param("o", org).param("v", version).param("r", expectedRevision).update() == 1;
    }

    /** Records which episode a rule placed on a date (encores are not recorded), so later runs continue the sequence. */
    public void insertOccurrence(UUID org, UUID rule, LocalDate date, UUID asset) {
        db.sql("insert into programming_occurrence(org_id, rule_id, air_date, asset_id) values (:o, :r, :d, :a)")
                .param("o", org).param("r", rule).param("d", date).param("a", asset).update();
    }

    public void insertDraftEntry(UUID org, UUID version, String kind, UUID asset, long offsetMs, Instant start, Instant end,
                                 String ownership, UUID rule, LocalDate date) {
        db.sql("""
                insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership, rule_id, rule_date)
                values (:o, :v, :k, :a, :off, :s, :e, :own, :r, :d)""")
                .param("o", org).param("v", version).param("k", kind).param("a", asset).param("off", offsetMs)
                .param("s", Times.utc(start)).param("e", Times.utc(end)).param("own", ownership).param("r", rule).param("d", date).update();
    }

    public record ActiveChannel(UUID orgId, String slug, String name, UUID channelId) {}

    /** Channels of active projects that have an enabled, unexpired rule (all tenants; caller bypasses RLS). */
    public List<ActiveChannel> channelsWithActiveRules() {
        return db.sql("""
                select distinct o.id as org_id, o.slug, o.name, c.id as channel_id from programming_rule r
                join channel c on c.org_id = r.org_id and c.id = r.channel_id join organization o on o.id = c.org_id
                where r.enabled and r.end_date >= current_date and c.status <> 'ARCHIVED' and o.status = 'ACTIVE'""")
                .query(ActiveChannel.class).list();
    }
}
