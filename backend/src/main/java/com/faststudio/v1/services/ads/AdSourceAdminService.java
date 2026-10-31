package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.PublicUrl;
import com.faststudio.v1.repositories.ads.AdSourceRepository;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.ads.sources.VastSource;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Editing a company's waterfall of ad connectors. The platform administrator manages it (Connectors page); a company
 * may add and manage only its own VAST tags (managed_by TENANT), and only with the AD_OWN_TAGS feature. Callers check
 * who may do what and set the tenant scope; this class enforces which rows each kind of caller may touch.
 */
@Service
public class AdSourceAdminService {
    private final AdSourceRepository repository;
    private final Audit audit;
    private final AdDecisionServiceV1 decisions;
    private final VastSource vast;

    public AdSourceAdminService(AdSourceRepository repository, Audit audit, AdDecisionServiceV1 decisions, VastSource vast) {
        this.repository = repository;
        this.audit = audit;
        this.decisions = decisions;
        this.vast = vast;
    }

    public record AdSourceRequest(@NotBlank @Size(max = 80) String name, @NotNull @Pattern(regexp = "DIRECT|VAST") String kind,
                                  @Size(max = 2000) String tagUrl, @Min(100) @Max(5000) int timeoutMs, boolean enabled) {}

    public record OrderRequest(@NotNull List<UUID> ids) {}

    public record TagTestRequest(@NotBlank @Size(max = 2000) String tagUrl, @Min(100) @Max(5000) int timeoutMs, boolean prepare) {}

    public record TestAd(String title, String advertiser, long durationMs, String mediaUrl, int trackingUrls, String creative) {}

    public record TagTest(String url, long ms, List<TestAd> ads, String error) {}

    /** The waterfall; a company never sees the platform's tag URLs (they carry the platform's ad server accounts). */
    public List<IAdSource.Config> list(UUID org, boolean platform) {
        return decisions.waterfall(org).stream().map(s -> platform || "TENANT".equals(s.managedBy()) ? s
                : new IAdSource.Config(s.id(), s.name(), s.kind(), null, s.timeoutMs(), s.position(), s.enabled(), s.managedBy())).toList();
    }

    public UUID add(UUID org, UUID actor, AdSourceRequest r, String managedBy) {
        String tag = checkTag(r);
        if ("TENANT".equals(managedBy) && !"VAST".equals(r.kind())) throw ApiException.bad("Companies can only add VAST ad tags.");
        materializeDefault(org);
        if ("DIRECT".equals(r.kind()) && repository.hasDirect(org))
            throw ApiException.conflict("DIRECT_EXISTS", "Direct-sold campaigns are already in the waterfall.");
        UUID id = repository.insert(org, r.name().trim(), r.kind(), tag, r.timeoutMs(), r.enabled(), managedBy);
        audit.log(org, actor, "ad-source.create", "ad_source", id, r.name() + " (" + managedBy.toLowerCase() + ")");
        return id;
    }

    /** Blank write-only credentials preserve the stored URL; ownership is checked before reading it. */
    public void update(UUID org, UUID actor, UUID id, AdSourceRequest r, String onlyManagedBy) {
        String tag = "VAST".equals(r.kind()) && (r.tagUrl() == null || r.tagUrl().isBlank())
                ? repository.vastTagUrl(org, id, onlyManagedBy).orElseThrow(() -> ApiException.notFound("Ad source"))
                : checkTag(r);
        if (!repository.update(org, id, r.kind(), r.name().trim(), tag, r.timeoutMs(), r.enabled(), onlyManagedBy))
            throw ApiException.notFound("Ad source");
        audit.log(org, actor, "ad-source.update", "ad_source", id, r.name());
    }

    public void delete(UUID org, UUID actor, UUID id, String onlyManagedBy) {
        if (!repository.delete(org, id, onlyManagedBy)) throw ApiException.notFound("Ad source");
        audit.log(org, actor, "ad-source.delete", "ad_source", id, "");
    }

    /** Platform only: the first id is asked first. */
    public List<IAdSource.Config> order(UUID org, UUID actor, List<UUID> ids) {
        materializeDefault(org);
        for (int i = 0; i < ids.size(); i++)
            repository.setPosition(org, ids.get(i), i);
        audit.log(org, actor, "ad-source.reorder", "ad_source", null, "");
        return list(org, true);
    }

    /**
     * Tag tester (HANDOVER §7.4): resolves the macros, follows wrappers and reports what came back and how long it
     * took, so "no demand" can be told apart from "misconfigured". prepare = also queue unseen creatives for
     * conditioning now, so they air from the very first break.
     */
    public TagTest test(UUID org, TagTestRequest r) {
        var src = new IAdSource.Config(null, "Tag tester", "VAST", r.tagUrl(), r.timeoutMs(), 0, true, "PLATFORM");
        var req = new IAdSource.Request(org, null, null, Instant.now(), 30_000, 30_000, "US", "tag-test", "",
                null, new IAdSource.Viewer("", "FAST Studio tag tester"), new HashSet<>());
        String url = VastSource.expand(r.tagUrl(), req);
        long start = System.nanoTime();
        try {
            List<VastSource.Ad> ads = vast.fetch(url, req.viewer(), start + r.timeoutMs() * 1_000_000L, 0, Map.of());
            long ms = (System.nanoTime() - start) / 1_000_000L;
            List<TestAd> out = new ArrayList<>();
            for (VastSource.Ad a : ads) {
                var c = r.prepare() ? vast.creative(org, src, a) : repository.creative(org, a.ref()).orElse(null);
                String status = c == null ? (r.prepare() ? "Queued for conditioning" : "New: will be conditioned when first served (or tick 'prepare')")
                        : "READY".equals(c.status()) ? "Ready (conditioned to " + c.durationMs() / 1000 + " s)" : c.status().charAt(0) + c.status().substring(1).toLowerCase();
                out.add(new TestAd(a.title(), a.advertiser(), a.durationMs(), null, a.tracking().values().stream().mapToInt(List::size).sum(), status));
            }
            return new TagTest("[REDACTED]", ms, out, null);
        } catch (ApiException e) {
            return new TagTest("[REDACTED]", (System.nanoTime() - start) / 1_000_000L, List.of(), "The provider request failed. Check the configured address and network policy.");
        } catch (Exception e) {
            return new TagTest("[REDACTED]", (System.nanoTime() - start) / 1_000_000L, List.of(), VastSource.reason(e));
        }
    }

    private static String checkTag(AdSourceRequest r) {
        if (!"VAST".equals(r.kind())) return null;
        if (r.tagUrl() == null || r.tagUrl().isBlank()) throw ApiException.bad("A VAST source needs its tag URL.");
        PublicUrl.check(r.tagUrl().replaceAll("\\[[A-Z_]+]", "x"));   // macros are filled in per request
        return r.tagUrl().trim();
    }

    /** The implicit default (direct-sold only) becomes a real row before the waterfall is edited. */
    private void materializeDefault(UUID org) {
        if (repository.count(org) == 0) repository.insertDirect(org, AdDecisionServiceV1.DEFAULT_DIRECT.name());
    }
}
