package com.faststudio.ads;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ad demand connector. A break is filled by working down the tenant's waterfall (ad_source rows, in order); each
 * source may return spots for at most the time still open, and whatever stays open plays slate.
 * DIRECT = our own direct-sold campaigns; VAST = any external ad server reached through a VAST tag.
 */
public interface AdSource {
    String kind();

    Fill fill(Config source, Request req);

    /** One ad_source row (or the default direct-sold source when a tenant has configured none). managedBy: PLATFORM or TENANT. */
    record Config(UUID id, String name, String kind,
                  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String tagUrl,
                  int timeoutMs, int position, boolean enabled, String managedBy) {
        @Override public String toString() { return "AdSource.Config[id=" + id + ", kind=" + kind + ", tagUrl=REDACTED]"; }
    }

    /** What a source may use to pick ads. remainingMs is a hard ceiling: overfilling clips the programme. */
    record Request(UUID org, UUID sessionId, UUID breakId, Instant at, long breakMs, long remainingMs, String country,
                   String channel, String genre, UUID programmeAssetId, Viewer viewer, Set<String> advertisers) {}

    /** The viewer as the edge saw them; ad servers use it for targeting and fraud checks. */
    record Viewer(String ip, String userAgent, String key, List<String> audiences) {
        public Viewer(String ip, String userAgent) { this(ip,userAgent,null,List.of()); }
        public static final Viewer NONE = new Viewer("", "");
    }

    record Fill(List<AdDecisionService.Slot> slots, List<AdDecisionService.Excluded> excluded) {}
}
