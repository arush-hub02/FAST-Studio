package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Feature;
import org.springframework.http.HttpStatus;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.dto.ads.AdsDto.Candidate;
import com.faststudio.v1.repositories.ads.AdSourceRepository;
import com.faststudio.v1.repositories.ads.CampaignRepository;
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
 * time every source left open goes to the project's house promos, and only what they can't fill plays slate. Every source and campaign left out comes with a reason.
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
    private final CampaignRepository campaigns;

    public AdDecisionServiceV1(Data data, AdSourceRepository repository, List<IAdSource> sources, PlatformTransactionManager manager, JsonMapper json, EdgePublisher publisher,
                              TenantConfigurationServiceV1 configuration, Secrets secrets, CampaignRepository campaigns) {
        this.data = data;
        this.repository = repository;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
        this.publisher = publisher;
        this.configuration=configuration;
        this.secrets = secrets;
        this.campaigns = campaigns;
        this.sources = sources.stream().collect(Collectors.toMap(IAdSource::kind, Function.identity()));
    }

    /** One spot. tracking: delivery event -> ad server URLs to call when it happens (external ads only). */
    /** levels: the quality-level folders the ad's media has (set when the decision is made; the Worker picks the nearest one). */
    /** delivery: R2 (our copy, mediaKey) or DIRECT (the ad server's own HLS at hlsUrl; mediaKey null). */
    public record Slot(UUID campaignId, String campaign, String advertiser, String mediaKey, long durationMs, BigDecimal cpm,
                       String source, Map<String, List<String>> tracking, List<Integer> levels, String delivery, String hlsUrl) {
        public static final String R2 = "R2", DIRECT = "DIRECT";

        public Slot(UUID campaignId, String campaign, String advertiser, String mediaKey, long durationMs, BigDecimal cpm,
                    String source, Map<String, List<String>> tracking, List<Integer> levels) {
            this(campaignId, campaign, advertiser, mediaKey, durationMs, cpm, source, tracking, levels, R2, null);
        }

        public Slot(UUID campaignId, String campaign, String advertiser, String mediaKey, long durationMs, BigDecimal cpm,
                    String source, Map<String, List<String>> tracking) {
            this(campaignId, campaign, advertiser, mediaKey, durationMs, cpm, source, tracking, null);
        }

        public int segments() { return (int) (durationMs / Times.SEG_MS); }

        public boolean direct() { return DIRECT.equals(delivery); }

        Slot withLevels(List<Integer> l) { return new Slot(campaignId, campaign, advertiser, mediaKey, durationMs, cpm, source, tracking, l, delivery, hlsUrl); }
    }

    public record Excluded(String campaign, String reason) {}

    public record Decision(UUID breakId, long breakMs, List<Slot> slots, long filledMs, List<Excluded> excluded, String summary, boolean preparing) {
        public Decision(UUID breakId, long breakMs, List<Slot> slots, long filledMs, List<Excluded> excluded, String summary) {
            this(breakId, breakMs, slots, filledMs, excluded, summary, false);
        }
    }

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country) {
        return decide(org, sessionId, breakId, breakStart, breakMs, programmeAssetId, country, IAdSource.Viewer.NONE);
    }

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country,
                           IAdSource.Viewer viewer) {
        return decide(org, sessionId, breakId, breakStart, breakMs, programmeAssetId, country, viewer, false);
    }

    public Decision decide(UUID org, UUID sessionId, UUID breakId, Instant breakStart, long breakMs, UUID programmeAssetId, String country,
                           IAdSource.Viewer viewer, boolean prefetch) {
        if (sessionId == null) return compute(org, null, breakId, breakStart, breakMs, programmeAssetId, country, viewer);
        return transactions.execute(tx -> {
            // Serialize reservations across sessions of the same permitted viewer, including other server instances.
            String key = org + ":" + (viewer.key() == null ? sessionId : viewer.key());
            repository.lockDecisions(key);
            Optional<Decision> prior = saved(org, sessionId, breakId);
            if (prior.isPresent()) return prior.get();
            Decision result = compute(org,sessionId,breakId,breakStart,breakMs,programmeAssetId,country,viewer);
            if (result.preparing()) {
                if (prefetch && breakStart.isAfter(Instant.now())) return result;
                // the break starts without the ads that were being prepared: promos rather than slate
                result = house(org, breakId, breakStart, country, result.slots(), result.excluded(), breakMs);
            }
            repository.saveDecision(org, sessionId, breakId, json.writeValueAsString(result));
            return result;
        });
    }

    public static Decision slateOnly(UUID breakId, long breakMs) {
        return new Decision(breakId, breakMs, List.of(), 0, List.of(), "Slate only, as configured in the schedule.");
    }

    public Optional<Decision> saved(UUID org, UUID sessionId, UUID breakId) {
        return repository.decision(org, sessionId, breakId).map(body -> json.readValue(body, Decision.class));
    }

    /** The tenant's waterfall in order; without configuration, direct-sold campaigns only. */
    public List<IAdSource.Config> waterfall(UUID org) {
        List<IAdSource.Config> rows = repository.waterfall(org).stream().map(s -> s.tagUrl() == null ? s
                : new IAdSource.Config(s.id(), s.name(), s.kind(), openTag(s.tagUrl()), s.timeoutMs(), s.position(), s.enabled(), s.managedBy(), s.delivery())).toList();
        return rows.isEmpty() ? List.of(DEFAULT_DIRECT) : rows;
    }

    /** A connection's delivery as it applies now: Direct only while the project has the Direct ad delivery feature. */
    public String effectiveDelivery(UUID org, IAdSource.Config source) {
        return IAdSource.DIRECT.equals(source.delivery()) && configuration.hasFeature(org, Feature.AD_DIRECT_DELIVERY) ? IAdSource.DIRECT : IAdSource.STORE_R2;
    }

    /** Saving Direct needs the feature (403 FEATURE_OFF otherwise). */
    public void requireDeliveryAllowed(UUID org, String delivery) {
        if (IAdSource.DIRECT.equals(delivery) && !configuration.hasFeature(org, Feature.AD_DIRECT_DELIVERY))
            throw new ApiException(HttpStatus.FORBIDDEN, "FEATURE_OFF", "Direct ad delivery isn't switched on for this company. Ask the platform administrator.");
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
        boolean preparing = false;
        long left = breakMs;
        String fmt = publisher.channelFormat(org, channel);
        for (IAdSource.Config src : waterfall(org)) {
            IAdSource impl = sources.get(src.kind());
            if (!src.enabled() || impl == null) continue;
            if (src.kind().equals("VAST") && !configuration.driverEnabled(org,"VAST")) continue;
            if (left < Times.SEG_MS) break;
            var fill = impl.fill(src.withDelivery(effectiveDelivery(org, src)),
                    new IAdSource.Request(org, sessionId, breakId, at, breakMs, left, country, channel, genre, programmeAssetId, viewer, advertisers, fmt));
            excluded.addAll(fill.excluded());
            preparing |= fill.preparing();
            for (Slot s : fill.slots()) {
                if (s.durationMs() > left || s.durationMs() < Times.SEG_MS) continue;   // never overfill
                if (s.direct()) {   // the ad server's own HLS: nothing of ours has to reach the edge
                    slots.add(s);
                    left -= s.durationMs();
                    continue;
                }
                if (!publisher.stored(s.mediaKey())) {
                    excluded.add(new Excluded(s.campaign(),"Creative has not reached the delivery edge yet"));
                    preparing = true;
                    continue;
                }
                slots.add(s.withLevels(publisher.levels(s.mediaKey())));
                left -= s.durationMs();
            }
        }
        // nothing sold yet but an ad is still being prepared: leave the time open so a retry can still place it
        if (slots.isEmpty() && preparing)
            return new Decision(breakId, breakMs, slots, 0, excluded, "Unfilled: no eligible ads. The standby slate plays for the whole break.", true);
        return house(org, breakId, at, country, slots, excluded, breakMs);
    }

    /**
     * Fills what the ad sources left open with the project's house promos (rotating, repeats allowed, no revenue),
     * so viewers see the channel's own spots instead of the standby slate. Only what no promo fits plays slate.
     */
    Decision house(UUID org, UUID breakId, Instant at, String country, List<Slot> sold, List<Excluded> excluded, long breakMs) {
        List<Slot> slots = new ArrayList<>(sold);
        long left = breakMs - slots.stream().mapToLong(Slot::durationMs).sum();
        int ads = slots.size();
        if (left >= Times.SEG_MS) {
            List<Candidate> promos = new ArrayList<>(campaigns.candidates(org).stream().filter(c -> c.house() && c.status().equals("ACTIVE")
                    && !at.isBefore(c.startsAt().toInstant()) && at.isBefore(c.endsAt().toInstant())
                    && (Times.codes(c.territories()).contains("WW") || Times.codes(c.territories()).contains(country))
                    && "READY".equals(c.creativeStatus()) && c.durationMs() >= Times.SEG_MS && publisher.stored(c.mediaKey())).toList());
            Collections.shuffle(promos, new Random(breakId.hashCode()));
            for (boolean added = true; added && left >= Times.SEG_MS; ) {   // rotate through the promos until none fits
                added = false;
                for (Candidate c : promos) {
                    if (c.durationMs() > left) continue;
                    slots.add(new Slot(c.id(), c.name(), c.advertiser(), c.mediaKey(), c.durationMs(), BigDecimal.ZERO, "House promos", Map.of())
                            .withLevels(publisher.levels(c.mediaKey())));
                    left -= c.durationMs();
                    added = true;
                }
            }
        }
        long filled = breakMs - left;
        int promos = slots.size() - ads;
        String what = (ads > 0 ? ads + " ad(s)" : "") + (ads > 0 && promos > 0 ? " and " : "") + (promos > 0 ? promos + " house promo(s)" : "");
        String summary = slots.isEmpty() ? "Unfilled: no eligible ads or house promos. The standby slate plays for the whole break."
                : "Filled " + filled / 1000 + " of " + breakMs / 1000 + " s with " + what + (left > 0 ? "; slate covers the last " + left / 1000 + " s." : ".");
        return new Decision(breakId, breakMs, slots, filled, excluded, summary, false);
    }
}
