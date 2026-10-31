package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Audit;
import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.PublicUrl;
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
    private final Data data;
    private final Audit audit;
    private final AdDecisionServiceV1 decisions;
    private final VastSource vast;

    public AdSourceAdminService(Data data, Audit audit, AdDecisionServiceV1 decisions, VastSource vast) {
        this.data = data;
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
        if ("DIRECT".equals(r.kind()) && data.db.sql("select count(*) from ad_source where org_id = :o and kind = 'DIRECT'").param("o", org).query(Integer.class).single() > 0)
            throw ApiException.conflict("DIRECT_EXISTS", "Direct-sold campaigns are already in the waterfall.");
        UUID id = data.db.sql("""
                insert into ad_source(org_id, name, kind, tag_url, timeout_ms, position, enabled, managed_by)
                values (:o, :n, :k, :u, :ms, (select coalesce(max(position), -1) + 1 from ad_source where org_id = :o), :en, :by) returning id""")
                .param("o", org).param("n", r.name().trim()).param("k", r.kind()).param("u", tag).param("ms", r.timeoutMs())
                .param("en", r.enabled()).param("by", managedBy).query(UUID.class).single();
        audit.log(org, actor, "ad-source.create", "ad_source", id, r.name() + " (" + managedBy.toLowerCase() + ")");
        return id;
    }

    /** Blank write-only credentials preserve the stored URL; ownership is checked before reading it. */
    public void update(UUID org, UUID actor, UUID id, AdSourceRequest r, String onlyManagedBy) {
        String tag = "VAST".equals(r.kind()) && (r.tagUrl() == null || r.tagUrl().isBlank())
                ? data.db.sql("select tag_url from ad_source where org_id=:o and id=:id and kind='VAST'"
                        + (onlyManagedBy == null ? "" : " and managed_by=:by"))
                    .param("o", org).param("id", id).params(by(onlyManagedBy)).query(String.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Ad source"))
                : checkTag(r);
        int n = data.db.sql("update ad_source set name = :n, tag_url = :u, timeout_ms = :ms, enabled = :en where org_id = :o and id = :id and kind = :k"
                        + (onlyManagedBy == null ? "" : " and managed_by = :by"))
                .param("n", r.name().trim()).param("u", tag).param("ms", r.timeoutMs()).param("en", r.enabled())
                .param("o", org).param("id", id).param("k", r.kind()).params(by(onlyManagedBy)).update();
        if (n == 0) throw ApiException.notFound("Ad source");
        audit.log(org, actor, "ad-source.update", "ad_source", id, r.name());
    }

    public void delete(UUID org, UUID actor, UUID id, String onlyManagedBy) {
        int n = data.db.sql("delete from ad_source where org_id = :o and id = :id" + (onlyManagedBy == null ? "" : " and managed_by = :by"))
                .param("o", org).param("id", id).params(by(onlyManagedBy)).update();
        if (n == 0) throw ApiException.notFound("Ad source");
        audit.log(org, actor, "ad-source.delete", "ad_source", id, "");
    }

    /** Platform only: the first id is asked first. */
    public List<IAdSource.Config> order(UUID org, UUID actor, List<UUID> ids) {
        materializeDefault(org);
        for (int i = 0; i < ids.size(); i++)
            data.db.sql("update ad_source set position = :p where org_id = :o and id = :id").param("p", i).param("o", org).param("id", ids.get(i)).update();
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
                var c = r.prepare() ? vast.creative(org, src, a) : data.db.sql("select id, status, media_key, duration_ms from asset where org_id = :o and source_ref = :r")
                        .param("o", org).param("r", a.ref()).query(VastSource.Creative.class).optional().orElse(null);
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

    private static Map<String, Object> by(String managedBy) {
        return managedBy == null ? Map.of() : Map.of("by", managedBy);
    }

    /** The implicit default (direct-sold only) becomes a real row before the waterfall is edited. */
    private void materializeDefault(UUID org) {
        if (data.db.sql("select count(*) from ad_source where org_id = :o").param("o", org).query(Integer.class).single() == 0)
            data.db.sql("insert into ad_source(org_id, name, kind, timeout_ms, position) values (:o, :n, 'DIRECT', 1000, 0)")
                    .param("o", org).param("n", AdDecisionServiceV1.DEFAULT_DIRECT.name()).update();
    }
}
