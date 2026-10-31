package com.faststudio.ads;

import com.faststudio.v1_0.services.tenancy.TenantConfigurationServiceV1_0;

import com.faststudio.core.Data;
import com.faststudio.core.Times;
import com.faststudio.rights.Rights;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.faststudio.edge.EdgePublisher;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fills one ad break for one viewer by working down the tenant's waterfall of ad connectors ({@link AdSource}):
 * direct-sold campaigns, then any number of external ad servers, each with its own timeout. Never overfills;
 * whatever stays open plays slate. Every source and campaign left out comes with a reason.
 */
@Service
public class AdDecisionService {
    static final AdSource.Config DEFAULT_DIRECT = new AdSource.Config(null, "Direct-sold campaigns", "DIRECT", null, 0, 0, true, "PLATFORM");

    private final Data data;
    private final Map<String, AdSource> sources;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final EdgePublisher publisher;
    private final TenantConfigurationServiceV1_0 configuration;

    public AdDecisionService(Data data, List<AdSource> sources, PlatformTransactionManager manager, JsonMapper json, EdgePublisher publisher,
                              TenantConfigurationServiceV1_0 configuration) {
        this.data = data;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
        this.publisher = publisher;
        this.configuration=configuration;
        this.sources = sources.stream().collect(Collectors.toMap(AdSource::kind, Function.identity()));
    }

    /** One spot. tracking: delivery event -> ad server URLs to call when it happens (external ads only). */
    public record Slot(UUID campaignId, String campaign, String advertiser, String mediaKey, long durationMs, BigDecimal cpm,
                       String source, Map<String, List<String>> tracking) {
        public int segments() { return (int) (durationMs / Times.SEG_MS); }
    }

    public record Excluded(String campaign, String reason) {}

    public record Decision(UUID breakId, long breakMs, List<Slot> slots, long filledMs, List<Excluded> excluded, String summary) {}

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country) {
        return decide(org, sessionId, breakId, breakStart, breakMs, programmeAssetId, country, AdSource.Viewer.NONE);
    }

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country,
                           AdSource.Viewer viewer) {
        if (sessionId == null) return compute(org, null, breakId, breakStart, breakMs, programmeAssetId, country, viewer);
        return transactions.execute(tx -> {
            // Serialize reservations across sessions of the same permitted viewer, including other server instances.
            String key = org + ":" + (viewer.key() == null ? sessionId : viewer.key());
            data.db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))").param("key",key).query((rs,n)->true).single();
            Optional<String> prior = data.db.sql("select decision from ad_decision where org_id=:o and session_id=:s and break_id=:b")
                    .param("o",org).param("s",sessionId).param("b",breakId).query(String.class).optional();
            if(prior.isPresent()) return json.readValue(prior.get(),Decision.class);
            Decision result = compute(org,sessionId,breakId,breakStart,breakMs,programmeAssetId,country,viewer);
            data.db.sql("insert into ad_decision(org_id,session_id,break_id,decision) values (:o,:s,:b,:d)")
                    .param("o",org).param("s",sessionId).param("b",breakId).param("d",json.writeValueAsString(result)).update();
            return result;
        });
    }

    /** The tenant's waterfall in order; without configuration, direct-sold campaigns only. */
    public List<AdSource.Config> waterfall(UUID org) {
        List<AdSource.Config> rows = data.db.sql("select id, name, kind, tag_url, timeout_ms, position, enabled, managed_by from ad_source where org_id = :o order by position, created_at")
                .param("o", org).query(AdSource.Config.class).list();
        return rows.isEmpty() ? List.of(DEFAULT_DIRECT) : rows;
    }

    Decision compute(UUID org, UUID sessionId, UUID breakId, Instant at, long breakMs, UUID programmeAssetId, String country, AdSource.Viewer viewer) {
        String genre = "";
        if (programmeAssetId != null) {
            Rights.Policy p = data.policy(org, programmeAssetId);
            if (p == null || !p.adsAllowed())
                return new Decision(breakId, breakMs, List.of(), 0, List.of(), "No ads: the programme's licence doesn't allow advertising.");
            genre = data.asset(org, programmeAssetId).genre();
        }
        String channel = data.db.sql("""
                select c.slug from schedule_entry e join schedule_version v on v.org_id = e.org_id and v.id = e.version_id
                join channel c on c.org_id = v.org_id and c.id = v.channel_id where e.org_id = :o and e.id = :b""")
                .param("o", org).param("b", breakId).query(String.class).optional().orElse("");
        List<Slot> slots = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        Set<String> advertisers = new HashSet<>();
        long left = breakMs;
        for (AdSource.Config src : waterfall(org)) {
            AdSource impl = sources.get(src.kind());
            if (!src.enabled() || impl == null) continue;
            if (src.kind().equals("VAST") && !configuration.driverEnabled(org,"VAST")) continue;
            if (left < Times.SEG_MS) break;
            var fill = impl.fill(src, new AdSource.Request(org, sessionId, breakId, at, breakMs, left, country, channel, genre, programmeAssetId, viewer, advertisers));
            excluded.addAll(fill.excluded());
            for (Slot s : fill.slots()) {
                if (s.durationMs() > left || s.durationMs() < Times.SEG_MS) continue;   // never overfill
                if (!publisher.stored(s.mediaKey())) {
                    excluded.add(new Excluded(s.campaign(),"Creative has not reached the delivery edge yet"));
                    continue;
                }
                slots.add(s);
                left -= s.durationMs();
            }
        }
        long filled = breakMs - left;
        String summary = slots.isEmpty() ? "Unfilled: no eligible ads. The standby slate plays for the whole break."
                : "Filled " + filled / 1000 + " of " + breakMs / 1000 + " s with " + slots.size() + " ad(s)" + (left > 0 ? "; slate covers the last " + left / 1000 + " s." : ".");
        return new Decision(breakId, breakMs, slots, filled, excluded, summary);
    }
}
