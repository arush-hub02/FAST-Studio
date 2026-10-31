package com.faststudio.v1.repositories.ops;

import com.faststudio.v1.dto.ops.OpsDto.Alert;
import com.faststudio.v1.dto.ops.OpsDto.AuditRow;
import com.faststudio.v1.dto.ops.OpsDto.CampaignStats;
import com.faststudio.v1.dto.ops.OpsDto.DayStat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** SQL for the dashboard counts, alerts, audit log and analytics of a project (RLS-scoped). */
@Repository
public class MonitoringRepository {
    private final JdbcClient db;

    public MonitoringRepository(JdbcClient db) { this.db = db; }

    // ------------------------------------------------------------------ dashboard counts

    public long channels(UUID o) { return count("select count(*) from channel where org_id = :o and status <> 'ARCHIVED'", o); }
    public long liveChannels(UUID o) { return count("select count(*) from channel where org_id = :o and status = 'LIVE'", o); }
    public long videos(UUID o) { return count("select count(*) from asset where org_id = :o and kind <> 'AD' and status <> 'ARCHIVED'", o); }
    public long readyVideos(UUID o) { return count("select count(*) from asset where org_id = :o and kind <> 'AD' and status = 'READY'", o); }
    public long processing(UUID o) { return count("select count(*) from asset where org_id = :o and status = 'PROCESSING'", o); }
    public long activeCampaigns(UUID o) { return count("select count(*) from campaign where org_id = :o and status = 'ACTIVE'", o); }
    public long openAlerts(UUID o) { return count("select count(*) from alert where org_id = :o and status <> 'RESOLVED'", o); }
    public long impressions(UUID o) { return count("select count(*) from ad_event where org_id = :o and event = 'IMPRESSION'", o); }
    public long viewerSessions(UUID o) { return count("select count(*) from playback_session where org_id = :o", o); }
    public long rightsSet(UUID o) { return count("select count(*) from rights_policy where org_id = :o and allowed_countries <> ''", o); }
    public long publishedSchedules(UUID o) { return count("select count(*) from schedule_version where org_id = :o and status = 'PUBLISHED'", o); }
    public long successfulDeliveries(UUID o) { return count("select count(*) from publication where org_id = :o and status = 'SUCCEEDED'", o); }

    private long count(String sql, UUID org) {
        return db.sql(sql).param("o", org).query(Long.class).single();
    }

    // ------------------------------------------------------------------ alerts

    /** Most severe first; {@code openOnly} hides resolved alerts. At most 100. */
    public List<Alert> alerts(UUID org, boolean openOnly) {
        return db.sql("select id, severity, title, detail, suggestion, link, status, resolution, created_at, updated_at from alert where org_id = :o"
                        + (openOnly ? " and status <> 'RESOLVED'" : "")
                        + " order by case severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end, created_at desc limit 100")
                .param("o", org).query(Alert.class).list();
    }

    public boolean acknowledge(UUID org, UUID id) {
        return db.sql("update alert set status = 'ACKNOWLEDGED', updated_at = now() where org_id = :o and id = :id and status = 'OPEN'")
                .param("o", org).param("id", id).update() > 0;
    }

    public boolean resolve(UUID org, UUID id, String resolution) {
        return db.sql("update alert set status = 'RESOLVED', resolution = :r, updated_at = now() where org_id = :o and id = :id and status <> 'RESOLVED'")
                .param("r", resolution).param("o", org).param("id", id).update() > 0;
    }

    // ------------------------------------------------------------------ audit

    public List<AuditRow> audit(UUID org, int limit) {
        return db.sql("""
                select e.id, coalesce(u.name || coalesce(' (' || v.name || ')', ''), v.name || ' (API)', 'System') as actor,
                  e.action, e.resource_type, e.resource_id, e.detail, e.created_at
                from audit_event e left join app_user u on u.id = e.actor_id left join organization v on v.id = e.via_org_id
                where e.org_id = :o order by e.id desc limit :l""")
                .param("o", org).param("l", limit).query(AuditRow.class).list();
    }

    // ------------------------------------------------------------------ analytics (a completion counts only with its impression)

    public record ChannelRow(UUID channelId, String channel, long sessions, long impressions, long completes, long adSeconds, long slateSeconds, BigDecimal revenue) {}

    public List<ChannelRow> channelStats(UUID org) {
        return db.sql("""
                select c.id as channel_id, c.name as channel,
                  (select count(*) from playback_session s where s.org_id = c.org_id and s.channel_id = c.id) as sessions,
                  count(e.id) filter (where e.event = 'IMPRESSION') as impressions,
                  count(e.id) filter (where e.event = 'COMPLETE' and exists (select 1 from ad_event i where i.session_id = e.session_id and i.break_id = e.break_id and i.campaign_id = e.campaign_id and i.event = 'IMPRESSION')) as completes,
                  coalesce(sum(e.seconds) filter (where e.event = 'IMPRESSION'), 0) as ad_seconds,
                  coalesce(sum(e.seconds) filter (where e.event = 'SLATE'), 0) as slate_seconds,
                  coalesce(sum(cp.cpm) filter (where e.event = 'IMPRESSION'), 0) / 1000 as revenue
                from channel c
                left join ad_event e on e.org_id = c.org_id and e.channel_id = c.id
                left join campaign cp on cp.org_id = e.org_id and cp.id = e.campaign_id
                where c.org_id = :o and c.status <> 'ARCHIVED' group by c.id, c.name order by c.name""").param("o", org).query(ChannelRow.class).list();
    }

    public List<CampaignStats> campaignStats(UUID org) {
        return db.sql("""
                select c.name as campaign, c.advertiser,
                  count(e.id) filter (where e.event = 'IMPRESSION') as impressions,
                  count(e.id) filter (where e.event = 'COMPLETE' and exists (select 1 from ad_event i where i.session_id = e.session_id and i.break_id = e.break_id and i.campaign_id = e.campaign_id and i.event = 'IMPRESSION')) as completes, c.cpm,
                  (count(e.id) filter (where e.event = 'IMPRESSION')) * c.cpm / 1000 as estimated_revenue
                from campaign c left join ad_event e on e.org_id = c.org_id and e.campaign_id = c.id
                where c.org_id = :o group by c.id, c.name, c.advertiser, c.cpm order by impressions desc, c.name""")
                .param("o", org).query(CampaignStats.class).list();
    }

    /** The last 7 days, including days with no activity. */
    public List<DayStat> lastWeek(UUID org) {
        return db.sql("""
                select d::date as day,
                  (select count(*) from ad_event e where e.org_id = :o and e.event = 'IMPRESSION' and e.occurred_at::date = d::date) as impressions,
                  (select count(*) from playback_session s where s.org_id = :o and s.created_at::date = d::date) as sessions
                from generate_series(current_date - 6, current_date, interval '1 day') d order by d""").param("o", org).query(DayStat.class).list();
    }
}
