package com.faststudio.v1.config.demo;

import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.services.programming.Planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * DEMO MODE ONLY. Seeds two fictional companies, development accounts, generated media and rights scenarios,
 * and on later starts extends the schedules of live demo channels so the demo never shows an empty channel.
 */
@Component
public class DemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private final Data data;
    private final PasswordEncoder encoder;
    private final Audit audit;
    private final TransactionTemplate tx;
    private final boolean demo;

    public DemoSeeder(Data data, PasswordEncoder encoder, Audit audit, TransactionTemplate tx, @Value("${faststudio.demo}") boolean demo) {
        this.data = data;
        this.encoder = encoder;
        this.audit = audit;
        this.tx = tx;
        this.demo = demo;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void run() {
        if (!demo) return;
        TenantScope.bypass(() -> { seedOrExtend(); return null; });   // demo setup is system work across tenants
    }

    void seedOrExtend() {
        long orgs = data.db.sql("select count(*) from organization where kind = 'CUSTOMER'").query(Long.class).single();
        if (orgs == 0) {
            tx.executeWithoutResult(s -> seed());
            log.info("Demo data seeded. Sign in with any demo account, password '{}'.", DemoAccounts.PASSWORD);
        } else {
            tx.executeWithoutResult(s -> extendLiveSchedules());
        }
        tx.executeWithoutResult(s -> ensurePartnerDemo());
    }

    /**
     * Demo partner (also added to existing demo data): "Partner TV", a fictional smart-TV channel app. Aurora shares
     * two channels with it (details, guide, stream, ad-break times; not viewing numbers) through its Partner TV destination.
     */
    void ensurePartnerDemo() {
        if (data.db.sql("select count(*) from organization where slug = 'partner-tv'").query(Integer.class).single() > 0) return;
        UUID au = data.db.sql("select id from organization where slug = 'aurora'").query(UUID.class).optional().orElse(null);
        if (au == null) return;
        UUID p = data.db.sql("""
                insert into organization(slug, name, country, timezone, plan, kind, max_channels)
                values ('partner-tv', 'Partner TV', 'US', 'America/New_York', 'Partner', 'PARTNER', 0) returning id""").query(UUID.class).single();
        UUID sam = data.db.sql("select id from app_user where email = 'partner@partnertv.test'").query(UUID.class).optional()
                .orElseGet(() -> user("partner@partnertv.test", "Sam Lee", false));
        member(p, sam, "PARTNER_ADMIN");
        UUID asha = data.db.sql("select id from app_user where email = 'owner@aurora.test'").query(UUID.class).single();
        UUID dest = data.db.sql("select id from destination where org_id = :o and name like 'Partner TV%' limit 1").param("o", au).query(UUID.class).optional().orElse(null);
        UUID link = data.db.sql("""
                insert into partner_link(owner_org_id, partner_org_id, scopes, destination_id, status, note, created_by, accepted_by, accepted_at)
                values (:o, :p, 'channels:read epg:read stream:read markers:read', :d, 'ACTIVE', 'Pilot agreement: two channels in the Partner TV app.', :a, :s, now())
                returning id""").param("o", au).param("p", p).param("d", dest).param("a", asha).param("s", sam).query(UUID.class).single();
        for (String slug : List.of("aurora-action", "classic-cinema"))
            data.db.sql("insert into partner_link_channel(link_id, org_id, channel_id) select :l, org_id, id from channel where org_id = :o and slug = :s")
                    .param("l", link).param("o", au).param("s", slug).update();
        log.info("Demo partner 'Partner TV' added (sign in as partner@partnertv.test).");
    }

    // ------------------------------------------------------------------ helpers

    UUID user(String email, String name, boolean platform) {
        return data.db.sql("insert into app_user(email, name, password_hash, platform_admin) values (:e,:n,:p,:pa) returning id")
                .param("e", email).param("n", name).param("p", encoder.encode(DemoAccounts.PASSWORD)).param("pa", platform).query(UUID.class).single();
    }

    UUID org(String slug, String name, String country, String tz) {
        return data.db.sql("insert into organization(slug, name, country, timezone, plan) values (:s,:n,:c,:tz,'Pilot') returning id")
                .param("s", slug).param("n", name).param("c", country).param("tz", tz).query(UUID.class).single();
    }

    void member(UUID org, UUID user, String role) {
        data.db.sql("insert into membership(org_id, user_id, role) values (:o,:u,:r)").param("o", org).param("u", user).param("r", role).update();
    }

    static final String[] LOOKS = {
            "gradients=size=640x360:rate=25:speed=0.012:c0=0x0f172a:c1=0x7c3aed:c2=0xf97316",
            "life=size=640x360:rate=25:mold=10:ratio=0.12:death_color=0x1e1b4b:life_color=0x22d3ee",
            "testsrc2=size=640x360:rate=25",
            "cellauto=size=640x360:rate=25:rule=110",
            "gradients=size=640x360:rate=25:speed=0.02:c0=0x042f2e:c1=0x14b8a6:c2=0xfde68a",
            "smptehdbars=size=640x360:rate=25"};
    int look = 0;

    UUID video(UUID org, String title, String desc, String kind, String genre, int seconds, String color, String series, Integer season, Integer ep) {
        long ms = seconds * 1000L;
        String cues = seconds >= 120 ? String.valueOf(Times.snapMs(ms / 2)) : "";
        return data.db.sql("""
                insert into asset(org_id, title, description, kind, genre, language, series, season, episode, duration_ms, cue_points, status, status_detail, color, generator)
                values (:o,:t,:d,:k,:g,'en',:s,:se,:ep,:ms,:cues,'PROCESSING','Generating demo video with ffmpeg',:c,:gen) returning id""")
                .param("o", org).param("t", title).param("d", desc).param("k", kind).param("g", genre).param("s", series).param("se", season)
                .param("ep", ep).param("ms", ms).param("cues", cues).param("c", color).param("gen", LOOKS[look++ % LOOKS.length])
                .query(UUID.class).single();
    }

    UUID ad(UUID org, String brand, String tagline, int seconds, String color) {
        return data.db.sql("""
                insert into asset(org_id, title, description, kind, genre, duration_ms, status, status_detail, color, generator)
                values (:o,:t,:d,'AD','Advertising',:ms,'PROCESSING','Generating demo creative with ffmpeg',:c,:gen) returning id""")
                .param("o", org).param("t", brand).param("d", "Fictional advertiser creative: " + tagline).param("ms", seconds * 1000L)
                .param("c", color).param("gen", "ad:" + color.replace("#", "0x") + "|" + tagline).query(UUID.class).single();
    }

    static OffsetDateTime at(Instant i) { return i == null ? null : i.atOffset(ZoneOffset.UTC); }

    void rights(UUID org, UUID asset, String allowed, String blocked, Instant from, Instant until, boolean ads, String notes) {
        data.db.sql("insert into rights_policy(org_id, asset_id, allowed_countries, blocked_countries, valid_from, valid_until, ads_allowed, notes) values (:o,:a,:al,:bl,:f,:u,:ads,:n)")
                .param("o", org).param("a", asset).param("al", allowed).param("bl", blocked).param("f", at(from)).param("u", at(until))
                .param("ads", ads).param("n", notes).update();
    }

    UUID destination(UUID org, String name, String kind, String mode, String format, int max, String notes) {
        return data.db.sql("insert into destination(org_id, name, kind, ad_mode, marker_format, max_ad_seconds_per_hour, notes) values (:o,:n,:k,:m,:f,:max,:notes) returning id")
                .param("o", org).param("n", name).param("k", kind).param("m", mode).param("f", format).param("max", max).param("notes", notes)
                .query(UUID.class).single();
    }

    void campaign(UUID org, String name, String advertiser, UUID creative, String status, String terr, int priority, String cpm, Instant now) {
        data.db.sql("insert into campaign(org_id, name, advertiser, status, starts_at, ends_at, territories, priority, cpm, creative_asset_id) values (:o,:n,:a,:s,:f,:e,:t,:p,:cpm,:c)")
                .param("o", org).param("n", name).param("a", advertiser).param("s", status).param("f", at(now.minusSeconds(86400 * 7)))
                .param("e", at(now.plusSeconds(86400 * 90))).param("t", terr).param("p", priority).param("cpm", new BigDecimal(cpm)).param("c", creative).update();
    }

    UUID channel(UUID org, UUID owner, String slug, String name, String desc, String genre, String tz, String color, String terr, UUID fallback) {
        UUID id = data.db.sql("""
                insert into channel(org_id, slug, name, description, genre, language, timezone, color, territories, fallback_asset_id)
                values (:o,:s,:n,:d,:g,'en',:tz,:c,:t,:fb) returning id""")
                .param("o", org).param("s", slug).param("n", name).param("d", desc).param("g", genre).param("tz", tz).param("c", color)
                .param("t", terr).param("fb", fallback).query(UUID.class).single();
        data.db.sql("insert into schedule_version(org_id, channel_id, number, status, created_by) values (:o,:c,0,'DRAFT',:u)")
                .param("o", org).param("c", id).param("u", owner).update();
        return id;
    }

    /** Plans as if media were ready: seeded videos finish processing within minutes; playout shows standby until then. */
    List<Planner.Item> plan(UUID org, UUID channelId, List<UUID> assetIds, Instant from, int hours) {
        Data.Channel ch = data.channel(org, channelId);
        Map<UUID, Data.Asset> all = data.assets(org);
        List<Data.Asset> pool = assetIds.stream().map(all::get).map(a -> new Data.Asset(a.id(), a.orgId(), a.title(), a.description(), a.kind(),
                a.genre(), a.language(), a.series(), a.season(), a.episode(), a.durationMs(), a.cuePoints(), "READY", a.statusDetail(),
                a.mediaKey(), a.color(), a.createdAt(), a.updatedAt(), a.mediaType())).toList();
        return Planner.autofill(ch, pool, data.policies(org), from, hours, Map.of()).items();
    }

    void insert(UUID org, UUID versionId, List<Planner.Item> items) {
        for (Planner.Item it : items)
            data.db.sql("insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership) values (:o,:v,:k,:a,:off,:s,:e,:own)")
                    .param("o", org).param("v", versionId).param("k", it.kind()).param("a", it.assetId()).param("off", it.offsetMs())
                    .param("s", at(it.start())).param("e", at(it.end())).param("own", it.ownership()).update();
    }

    void publish(UUID org, UUID channelId, UUID actor, String note) {
        UUID draft = data.draft(org, channelId).id();
        int next = data.db.sql("select coalesce(max(number),0)+1 from schedule_version where channel_id = :c").param("c", channelId).query(Integer.class).single();
        UUID v = data.db.sql("insert into schedule_version(org_id, channel_id, number, status, note, created_by, published_at) values (:o,:c,:n,'PUBLISHED',:note,:u,now()) returning id")
                .param("o", org).param("c", channelId).param("n", next).param("note", note).param("u", actor).query(UUID.class).single();
        data.db.sql("insert into schedule_entry(org_id, version_id, kind, asset_id, asset_offset_ms, start_at, end_at, ownership) select org_id, :v, kind, asset_id, asset_offset_ms, start_at, end_at, ownership from schedule_entry where version_id = :d")
                .param("v", v).param("d", draft).update();
        data.db.sql("update schedule_version set status='SUPERSEDED' where channel_id = :c and status='PUBLISHED' and id <> :v").param("c", channelId).param("v", v).update();
        data.db.sql("update channel set published_version_id = :v, status = 'LIVE' where id = :c").param("v", v).param("c", channelId).update();
        audit.log(org, actor, "schedule.publish", "channel", channelId, "version " + next + ": " + note);
    }

    // ------------------------------------------------------------------ seed

    void seed() {
        Instant now = Instant.now();
        UUID asha = user("owner@aurora.test", "Asha Rao", false);
        UUID ravi = user("operator@aurora.test", "Ravi Menon", false);
        UUID meera = user("content@aurora.test", "Meera Iyer", false);
        UUID dev = user("ads@aurora.test", "Dev Kapoor", false);
        UUID lena = user("analyst@aurora.test", "Lena Fischer", false);
        UUID tom = user("owner@northwind.test", "Tom Hale", false);
        user("admin@platform.test", "Platform Admin", true);

        // ---------------- Aurora Media Network (main demo tenant)
        UUID au = org("aurora", "Aurora Media Network", "IN", "Asia/Kolkata");
        member(au, asha, "OWNER");
        member(au, ravi, "CHANNEL_OPERATOR");
        member(au, meera, "CONTENT_MANAGER");
        member(au, dev, "AD_OPS");
        member(au, lena, "ANALYST");

        UUID neon = video(au, "Neon Pursuit", "A courier races across a rain-soaked city before midnight.", "MOVIE", "Action", 360, "#ef4444", null, null, null);
        UUID steel = video(au, "Steel Horizon", "Two rival pilots must land a damaged cargo plane together.", "MOVIE", "Action", 300, "#f97316", null, null, null);
        UUID lastSignal = video(au, "Last Signal", "A radio operator hears a message from a ship that sank 40 years ago.", "MOVIE", "Thriller", 240, "#8b5cf6", null, null, null);
        UUID moonlit = video(au, "Moonlit Avenue", "A jazz pianist and a detective share one long night.", "MOVIE", "Classic", 300, "#64748b", null, null, null);
        UUID ident = video(au, "Aurora Channel Ident", "Short station ident used as fallback filler.", "SHORT", "Promo", 30, "#6366f1", null, null, null);
        UUID velvet = video(au, "The Velvet Clock", "A watchmaker's apprentice uncovers a 1950s mystery.", "MOVIE", "Classic", 360, "#a16207", null, null, null);
        UUID harbor = video(au, "Harbor Lights", "A lighthouse keeper's last summer on the island.", "MOVIE", "Classic", 270, "#0e7490", null, null, null);
        UUID kerala = video(au, "Slow Roads: Coastlines of Kerala", "Backwaters, fishing nets and monsoon skies.", "EPISODE", "Travel", 300, "#059669", "Slow Roads", 1, 1);
        UUID desert = video(au, "Slow Roads: Desert Light", "Dawn to dusk across the Thar.", "EPISODE", "Travel", 300, "#ca8a04", "Slow Roads", 1, 2);
        UUID markets = video(au, "Mountain Markets", "Trading days in high Himalayan villages.", "EPISODE", "Travel", 240, "#0d9488", null, null, null);
        UUID pip = video(au, "Pip & the Paper Moon", "A paper bird tries to reach the moon.", "SHORT", "Family", 180, "#ec4899", null, null, null);
        UUID robot = video(au, "Robot Garden", "A tiny robot learns to grow tomatoes.", "SHORT", "Family", 180, "#22c55e", null, null, null);

        UUID glow = ad(au, "Glow Cola", "Taste the sparkle", 15, "#e11d48");
        UUID zenith = ad(au, "Zenith Phones", "Smarter in every light", 30, "#2563eb");
        UUID bluebird = ad(au, "Bluebird Air", "Fly further for less", 30, "#0891b2");
        UUID oats = ad(au, "Crunchy Oats", "Breakfast, upgraded", 15, "#d97706");
        UUID nova = ad(au, "Nova Bank", "Banking that keeps up", 30, "#16a34a");
        UUID promo = ad(au, "Aurora Promo", "Stay tuned to Aurora", 15, "#7c3aed");

        Instant year = now.plusSeconds(365L * 86400);
        rights(au, neon, "IN,GB,US", "", now.minusSeconds(30L * 86400), year, true, "Fictional licence: India, UK and US for 12 months.");
        rights(au, steel, "IN", "", now.minusSeconds(30L * 86400), year, true, "India only — viewers elsewhere see a regional block screen.");
        rights(au, lastSignal, "WW", "US,CA", now.minusSeconds(60L * 86400), now.plusSeconds(5L * 86400), true, "Worldwide except US/Canada; licence ends in 5 days.");
        rights(au, moonlit, "IN,GB,US", "", now.minusSeconds(10L * 86400), year, true, "");
        rights(au, ident, "WW", "", null, null, true, "Own content.");
        rights(au, velvet, "WW", "", now.minusSeconds(90L * 86400), year, false, "Estate requires ad-free broadcast: no ad breaks allowed.");
        rights(au, harbor, "IN,GB", "", now.minusSeconds(400L * 86400), now.minusSeconds(86400), true, "Licence expired yesterday.");
        rights(au, kerala, "IN,GB,US", "", now.minusSeconds(5L * 86400), year, true, "");
        rights(au, desert, "IN,GB,US", "", now.minusSeconds(5L * 86400), year, true, "");
        rights(au, markets, "", "", null, year, true, "Incomplete: distributor hasn't confirmed territories yet.");
        rights(au, pip, "WW", "", null, year, true, "");
        // Robot Garden deliberately has no rights policy at all.

        campaign(au, "Glow Cola — Summer Sparkle", "Glow Beverages (fictional)", glow, "ACTIVE", "WW", 8, "12.00", now);
        campaign(au, "Zenith X launch", "Zenith Electronics (fictional)", zenith, "ACTIVE", "IN,GB", 7, "18.00", now);
        campaign(au, "Bluebird — Fly Further", "Bluebird Airlines (fictional)", bluebird, "ACTIVE", "GB,US", 6, "22.00", now);
        campaign(au, "Crunchy Oats breakfast", "Crunchy Foods (fictional)", oats, "ACTIVE", "WW", 5, "9.00", now);
        campaign(au, "Nova Bank brand", "Nova Bank (fictional)", nova, "PAUSED", "IN", 7, "25.00", now);
        campaign(au, "House: Stay tuned", "Aurora Media Network", promo, "ACTIVE", "WW", 1, "0.00", now);

        destination(au, "Aurora web player (SSAI)", "OWNED_WEB", "SSAI", "DATERANGE", 720, "Our own web app. Ads are stitched into the stream on the server.");
        destination(au, "Aurora web player (SGAI beta)", "OWNED_WEB", "SGAI", "DATERANGE", 720, "Same app using HLS Interstitials: the player fetches the ads itself.");
        destination(au, "Partner TV (simulated)", "PARTNER_TV", "PASSTHROUGH", "CUE_OUT", 600, "Fictional TV platform that inserts its own ads; wants CUE-OUT/CUE-IN tags.");
        destination(au, "Smart TV Hub (simulated)", "PARTNER_TV", "SSAI", "DATERANGE", 360, "Fictional platform with a strict 6 min/hour ad-load limit.");

        Instant start = now.minusSeconds(20 * 60);
        UUID action = channel(au, asha, "aurora-action", "Aurora Action", "Non-stop chases, heists and high-altitude thrills.", "Action", "Asia/Kolkata", "#ef4444", "IN,GB,US", ident);
        insert(au, data.draft(au, action).id(), plan(au, action, List.of(neon, steel, lastSignal, moonlit), start, 30));
        publish(au, action, ravi, "Initial lineup");

        UUID classic = channel(au, asha, "classic-cinema", "Classic Cinema", "Golden-age mysteries and romances, uninterrupted.", "Classic", "Asia/Kolkata", "#a16207", "IN,GB", ident);
        insert(au, data.draft(au, classic).id(), plan(au, classic, List.of(velvet, harbor, moonlit), start, 30));
        publish(au, classic, ravi, "Initial lineup");

        UUID travel = channel(au, asha, "travel-discovery", "Travel & Discovery", "Slow journeys through India and beyond.", "Travel", "Asia/Kolkata", "#059669", "IN,GB,US", ident);
        List<Planner.Item> tItems = new ArrayList<>(plan(au, travel, List.of(kerala, desert), now.plusSeconds(120), 6));
        Instant tEnd = tItems.isEmpty() ? Times.snapUp(now) : tItems.getLast().end();
        tItems.add(new Planner.Item("PROGRAM", markets, 0, tEnd, tEnd.plusSeconds(240), null));   // incomplete rights → validation error to fix in the demo
        insert(au, data.draft(au, travel).id(), tItems);

        channel(au, asha, "family-entertainment", "Family Entertainment", "Gentle stories for younger viewers.", "Family", "Asia/Kolkata", "#ec4899", "IN,GB", null);

        // ---------------- Northwind Sports (second tenant, proves isolation)
        UUID nw = org("northwind", "Northwind Sports", "GB", "Europe/London");
        member(nw, tom, "OWNER");
        member(nw, asha, "ANALYST");            // one person, two organizations, different roles
        UUID matchday = video(nw, "Matchday Classics: The '98 Final", "Extended highlights of a legendary (fictional) cup final.", "MOVIE", "Sports", 300, "#1d4ed8", null, null, null);
        UUID training = video(nw, "Inside the Training Ground", "A week with a fictional academy squad.", "EPISODE", "Sports", 240, "#15803d", null, null, null);
        UUID pitch = ad(nw, "Pitchside Energy", "Fuel the second half", 15, "#f59e0b");
        rights(nw, matchday, "GB,IE", "", now.minusSeconds(86400), year, true, "");
        rights(nw, training, "GB,IE", "", now.minusSeconds(86400), year, true, "");
        campaign(nw, "Pitchside Energy launch", "Pitchside Drinks (fictional)", pitch, "ACTIVE", "GB,IE", 6, "15.00", now);
        destination(nw, "Northwind web player", "OWNED_WEB", "SSAI", "DATERANGE", 720, "");
        UUID sports = channel(nw, tom, "sports-classics", "Northwind Sports Classics", "Great matches, replayed.", "Sports", "Europe/London", "#1d4ed8", "GB,IE", null);
        insert(nw, data.draft(nw, sports).id(), plan(nw, sports, List.of(matchday, training), start, 30));
        publish(nw, sports, tom, "Initial lineup");
    }

    /** Later demo starts: keep live demo channels on air by auto-filling and publishing (recorded in the audit log). */
    void extendLiveSchedules() {
        Instant now = Instant.now();
        for (UUID org : data.db.sql("select id from organization where kind = 'CUSTOMER'").query(UUID.class).list()) {
            for (Data.Channel ch : data.channels(org)) {
                if (!ch.status().equals("LIVE")) continue;
                Instant end = data.coverageEnd(org, ch.publishedVersionId());
                if (end != null && end.isAfter(now.plusSeconds(3 * 3600))) continue;
                UUID draft = data.draft(org, ch.id()).id();
                Instant from = end != null && end.isAfter(now) ? end : now.minusSeconds(60);
                List<UUID> pool = data.db.sql("select distinct asset_id from schedule_entry where version_id = :v and kind = 'PROGRAM'")
                        .param("v", ch.publishedVersionId()).query(UUID.class).list();
                Map<UUID, Data.Asset> assets = data.assets(org);
                var plan = Planner.autofill(ch, pool.stream().map(assets::get).filter(Objects::nonNull).toList(), data.policies(org), from, 30, Map.of());
                data.db.sql("delete from schedule_entry where version_id = :v and end_at > :f").param("v", draft).param("f", at(from)).update();
                insert(org, draft, plan.items());
                publish(org, ch.id(), null, "Demo mode: schedule auto-extended on startup");
                log.info("Demo: extended schedule of {}", ch.name());
            }
        }
    }
}
