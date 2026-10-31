package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.repositories.ads.AdSourceRepository;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.rights.Rights;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.cloudflare.EdgePublisher;


import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fills one ad break for one viewer by working down the tenant's waterfall of ad connectors ({@link IAdSource}):
 * direct-sold campaigns, then any number of external ad servers, each with its own timeout. Never overfills;
 * whatever stays open plays slate. Every source and campaign left out comes with a reason.
 */
@Service
public class AdDecisionServiceV1 {
    static final IAdSource.Config DEFAULT_DIRECT = new IAdSource.Config(null, "Direct-sold campaigns", "DIRECT", null, 0, 0, true, "PLATFORM");

    private final Data data;
    private final AdSourceRepository repository;
    private final Map<String, IAdSource> sources;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final EdgePublisher publisher;
    private final TenantConfigurationServiceV1 configuration;
    private final Secrets secrets;

    public AdDecisionServiceV1(Data data, AdSourceRepository repository, List<IAdSource> sources, PlatformTransactionManager manager, JsonMapper json, EdgePublisher publisher,
                              TenantConfigurationServiceV1 configuration, Secrets secrets) {
        this.data = data;
        this.repository = repository;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
        this.publisher = publisher;
        this.configuration=configuration;
        this.secrets = secrets;
        this.sources = sources.stream().collect(Collectors.toMap(IAdSource::kind, Function.identity()));
    }

    /** One spot. tracking: delivery event -> ad server URLs to call when it happens (external ads only). */
    public record Slot(UUID campaignId, String campaign, String advertiser, String mediaKey, long durationMs, BigDecimal cpm,
                       String source, Map<String, List<String>> tracking) {
        public int segments() { return (int) (durationMs / Times.SEG_MS); }
    }

    public record Excluded(String campaign, String reason) {}

    public record Decision(UUID breakId, long breakMs, List<Slot> slots, long filledMs, List<Excluded> excluded, String summary) {}

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country) {
        return decide(org, sessionId, breakId, breakStart, breakMs, programmeAssetId, country, IAdSource.Viewer.NONE);
    }

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country,
                           IAdSource.Viewer viewer) {
        if (sessionId == null) return compute(org, null, breakId, breakStart, breakMs, programmeAssetId, country, viewer);
        return transactions.execute(tx -> {
            // Serialize reservations across sessions of the same permitted viewer, including other server instances.
            String key = org + ":" + (viewer.key() == null ? sessionId : viewer.key());
            repository.lockDecisions(key);
            Optional<String> prior = repository.decision(org, sessionId, breakId);
            if(prior.isPresent()) return json.readValue(prior.get(),Decision.class);
            Decision result = compute(org,sessionId,breakId,breakStart,breakMs,programmeAssetId,country,viewer);
            repository.saveDecision(org, sessionId, breakId, json.writeValueAsString(result));
            return result;
        });
    }

    /** The tenant's waterfall in order; without configuration, direct-sold campaigns only. */
    public List<IAdSource.Config> waterfall(UUID org) {
        List<IAdSource.Config> rows = repository.waterfall(org).stream().map(s -> s.tagUrl() == null ? s
                : new IAdSource.Config(s.id(), s.name(), s.kind(), openTag(s.tagUrl()), s.timeoutMs(), s.position(), s.enabled(), s.managedBy())).toList();
        return rows.isEmpty() ? List.of(DEFAULT_DIRECT) : rows;
    }

    /**
     * Ad-tag URLs are stored encrypted (they carry ad-server account details). Values from before encryption are still
     * plaintext until AdSourceAdminService re-seals them at start-up; Base64 never contains "://", so they are told apart.
     */
    public String openTag(String stored) {
        return stored == null || stored.contains("://") ? stored : secrets.open(stored);
    }

    public String sealTag(String plain) { return secrets.seal(plain); }

    Decision compute(UUID org, UUID sessionId, UUID breakId, Instant at, long breakMs, UUID programmeAssetId, String country, IAdSource.Viewer viewer) {
        String genre = "";
        if (programmeAssetId != null) {
            Rights.Policy p = data.policy(org, programmeAssetId);
            if (p == null || !p.adsAllowed())
                return new Decision(breakId, breakMs, List.of(), 0, List.of(), "No ads: the programme's licence doesn't allow advertising.");
            genre = data.asset(org, programmeAssetId).genre();
        }
        String channel = repository.channelSlugOfBreak(org, breakId);
        List<Slot> slots = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        Set<String> advertisers = new HashSet<>();
        long left = breakMs;
        for (IAdSource.Config src : waterfall(org)) {
            IAdSource impl = sources.get(src.kind());
            if (!src.enabled() || impl == null) continue;
            if (src.kind().equals("VAST") && !configuration.driverEnabled(org,"VAST")) continue;
            if (left < Times.SEG_MS) break;
            var fill = impl.fill(src, new IAdSource.Request(org, sessionId, breakId, at, breakMs, left, country, channel, genre, programmeAssetId, viewer, advertisers));
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
