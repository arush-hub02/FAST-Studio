package com.faststudio.v1.repositories.playout;

import com.faststudio.v1.core.common.Data.Entry;
import com.faststudio.v1.dto.playout.PlaybackDto.EventRow;
import com.faststudio.v1.dto.playout.PlaybackDto.Session;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for public playback sessions. The organization lookup runs before row-level security is set to that tenant. */
@Repository
public class PlaybackRepository {
    private final JdbcClient db;

    public PlaybackRepository(JdbcClient db) { this.db = db; }

    public record OrgRow(UUID id, String status) {}

    public Optional<OrgRow> organizationBySlug(String slug) {
        return db.sql("select id, status from organization where slug = :s").param("s", slug).query(OrgRow.class).optional();
    }

    public Optional<UUID> channelBySlug(UUID org, String slug) {
        return db.sql("select id from channel where org_id = :o and slug = :s").param("o", org).param("s", slug).query(UUID.class).optional();
    }

    public record DestRow(UUID id, String name, String kind, String adMode, String markerFormat) {}

    /** The given destination, or the project's default web player (SSAI first). */
    public Optional<DestRow> destination(UUID org, UUID destination) {
        String sql = "select id, name, kind, ad_mode, marker_format from destination where org_id = :o "
                + (destination != null ? "and id = :d" : "and kind = 'OWNED_WEB' order by (ad_mode = 'SSAI') desc, created_at limit 1");
        var q = db.sql(sql).param("o", org);
        if (destination != null) q = q.param("d", destination);
        return q.query(DestRow.class).optional();
    }

    public UUID insertSession(UUID org, UUID channel, UUID destination, String country, String adMode,
                              String viewerKey, String audiences, OffsetDateTime personalizedUntil) {
        return db.sql("""
                insert into playback_session(org_id, channel_id, destination_id, country, ad_mode, viewer_key, audiences, personalized_until)
                values (:o, :c, :d, :k, :m, :vk, :aud, :until) returning id""")
                .param("o", org).param("c", channel).param("d", destination).param("k", country).param("m", adMode)
                .param("vk", viewerKey).param("aud", audiences).param("until", personalizedUntil).query(UUID.class).single();
    }

    /** The viewer withdrew permission: later decisions are contextual only. */
    public void forgetViewer(UUID org, UUID session) {
        db.sql("update playback_session set viewer_key = null, audiences = '', personalized_until = null where org_id = :o and id = :s")
                .param("o", org).param("s", session).update();
    }

    /** The session's 12 most recent delivery events, newest first. */
    public List<EventRow> recentEvents(UUID org, UUID session) {
        return db.sql("""
                select e.event, coalesce(c.name, ca.title, 'Slate (unfilled)') as campaign, e.seconds, e.occurred_at
                from ad_event e left join campaign c on c.org_id = e.org_id and c.id = e.campaign_id
                left join asset ca on ca.org_id = e.org_id and ca.id = e.campaign_id
                where e.org_id = :o and e.session_id = :s order by e.id desc limit 12""")
                .param("o", org).param("s", session).query(EventRow.class).list();
    }

    /** A session with its destination, looked up by id alone (callers run it as the system, then scope to its tenant). */
    public Optional<Session> session(UUID id) {
        return db.sql("""
                select s.id, s.org_id, s.channel_id, s.destination_id, s.country, s.ad_mode, s.created_at,
                       d.marker_format, d.kind as dest_kind, d.name as dest_name,s.viewer_key,s.audiences,s.personalized_until
                from playback_session s join destination d on d.org_id = s.org_id and d.id = s.destination_id where s.id = :id""")
                .param("id", id).query(Session.class).optional();
    }

    /** One ad delivery beacon; duplicates are ignored. Returns 1 when stored, 0 when it was a duplicate. */
    public int insertAdEvent(UUID org, UUID session, UUID channel, UUID breakId, UUID campaign, String event, int seconds) {
        return db.sql("""
                insert into ad_event(org_id, session_id, channel_id, break_id, campaign_id, event, seconds)
                values (:o,:s,:c,:b,:camp,:e,:sec) on conflict do nothing""")
                .param("o", org).param("s", session).param("c", channel).param("b", breakId)
                .param("camp", campaign).param("e", event).param("sec", seconds).update();
    }

    /** An ad break of this channel in any published (number > 0) schedule version. */
    public Optional<Entry> publishedBreak(UUID org, UUID channel, UUID breakId) {
        return db.sql("select e.id,e.version_id,e.kind,e.asset_id,e.asset_offset_ms,e.start_at,e.end_at,e.ownership from schedule_entry e join schedule_version v on v.org_id=e.org_id and v.id=e.version_id where e.org_id=:o and e.id=:id and e.kind='AD_BREAK' and v.channel_id=:ch and v.number>0")
                .param("o", org).param("id", breakId).param("ch", channel).query(Entry.class).optional();
    }

    /** How a destination receives breaks: ad mode and marker format. */
    public record Destination(String name, String kind, String adMode, String markerFormat) {}

    public List<Destination> destinations(UUID org) {
        return db.sql("select name, kind, ad_mode, marker_format from destination where org_id = :o order by created_at")
                .param("o", org).query(Destination.class).list();
    }
}
