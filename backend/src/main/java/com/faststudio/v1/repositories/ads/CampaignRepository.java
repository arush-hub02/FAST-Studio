package com.faststudio.v1.repositories.ads;

import com.faststudio.v1.dto.ads.AdsDto.Campaign;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SQL for direct-sold campaigns (RLS-scoped to the project). */
@Repository
public class CampaignRepository {
    private final JdbcClient db;

    public CampaignRepository(JdbcClient db) { this.db = db; }

    /** Campaigns with impression/complete counts; a completion counts only when its impression was recorded too. */
    public List<Campaign> campaigns(UUID org) {
        return db.sql("""
                select c.id, c.name, c.advertiser, c.status, c.starts_at, c.ends_at, c.territories, c.priority, c.cpm, c.creative_asset_id,
                       a.title as creative_title, a.status as creative_status, a.duration_ms as creative_duration_ms, a.color as creative_color, a.media_key,
                       (select count(*) from ad_event e where e.org_id = c.org_id and e.campaign_id = c.id and e.event = 'IMPRESSION') as impressions,
                       (select count(*) from ad_event e where e.org_id = c.org_id and e.campaign_id = c.id and e.event = 'COMPLETE'
                          and exists (select 1 from ad_event i where i.session_id = e.session_id and i.break_id = e.break_id
                                      and i.campaign_id = e.campaign_id and i.event = 'IMPRESSION')) as completes,
                       null as thumb_url, c.audiences, c.frequency_cap
                from campaign c join asset a on a.org_id = c.org_id and a.id = c.creative_asset_id
                where c.org_id = :o order by c.status, c.priority desc, c.name""").param("o", org).query(Campaign.class).list();
    }

    public record Fields(String name, String advertiser, UUID creativeAssetId, OffsetDateTime startsAt, OffsetDateTime endsAt, String territories,
                         int priority, BigDecimal cpm, String audiences, Integer frequencyCap) {}

    public UUID insert(UUID org, Fields f) {
        return db.sql("""
                insert into campaign(org_id, name, advertiser, creative_asset_id, starts_at, ends_at, territories, priority, cpm, audiences, frequency_cap)
                values (:o, :n, :a, :cr, :s, :e, :terr, :p, :cpm, :aud, :cap) returning id""")
                .param("o", org).param("n", f.name()).param("a", f.advertiser()).param("cr", f.creativeAssetId()).param("s", f.startsAt())
                .param("e", f.endsAt()).param("terr", f.territories()).param("p", f.priority()).param("cpm", f.cpm())
                .param("aud", f.audiences()).param("cap", f.frequencyCap()).query(UUID.class).single();
    }

    /** Null audiences / frequency cap keep the stored values. False when the campaign does not exist. */
    public boolean update(UUID org, UUID id, Fields f) {
        return db.sql("""
                update campaign set name = :n, advertiser = :a, creative_asset_id = :cr, starts_at = :s, ends_at = :e, territories = :terr,
                priority = :p, cpm = :cpm, audiences = coalesce(:aud, audiences), frequency_cap = coalesce(:cap, frequency_cap)
                where org_id = :o and id = :id""")
                .param("n", f.name()).param("a", f.advertiser()).param("cr", f.creativeAssetId()).param("s", f.startsAt()).param("e", f.endsAt())
                .param("terr", f.territories()).param("p", f.priority()).param("cpm", f.cpm()).param("aud", f.audiences())
                .param("cap", f.frequencyCap()).param("o", org).param("id", id).update() > 0;
    }

    public boolean setStatus(UUID org, UUID id, String status) {
        return db.sql("update campaign set status = :s where org_id = :o and id = :id").param("s", status).param("o", org).param("id", id).update() > 0;
    }
}
