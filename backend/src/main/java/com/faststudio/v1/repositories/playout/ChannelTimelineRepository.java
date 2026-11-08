package com.faststudio.v1.repositories.playout;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** SQL for channel_timeline: each channel's segment-numbering anchor (util/cloudflare/Timeline). */
@Repository
public class ChannelTimelineRepository {
    private final JdbcClient db;

    public ChannelTimelineRepository(JdbcClient db) { this.db = db; }

    public record Anchor(long atMs, long seq, int targetS) {}

    public Optional<Anchor> get(UUID org, UUID channel) {
        return db.sql("select at_ms, seq, target_s from channel_timeline where org_id = :o and channel_id = :c")
                .param("o", org).param("c", channel).query(Anchor.class).optional();
    }

    /** Moves the anchor forward only (several servers may publish at once; they compute the same values). */
    public void save(UUID org, UUID channel, Anchor a) {
        db.sql("""
                insert into channel_timeline(channel_id, org_id, at_ms, seq, target_s) values (:c, :o, :at, :seq, :t)
                on conflict (channel_id) do update set
                  at_ms = case when excluded.at_ms > channel_timeline.at_ms then excluded.at_ms else channel_timeline.at_ms end,
                  seq = case when excluded.at_ms > channel_timeline.at_ms then excluded.seq else channel_timeline.seq end,
                  target_s = greatest(channel_timeline.target_s, excluded.target_s), updated_at = now()
                where channel_timeline.org_id = :o and (excluded.at_ms > channel_timeline.at_ms or excluded.target_s > channel_timeline.target_s)""")
                .param("c", channel).param("o", org).param("at", a.atMs()).param("seq", a.seq()).param("t", a.targetS()).update();
    }
}
