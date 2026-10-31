package com.faststudio.v1.util.cloudflare;

import com.faststudio.v1.core.common.Data.Asset;
import com.faststudio.v1.core.common.Data.Channel;
import com.faststudio.v1.core.common.Data.Entry;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.core.tenant.TenantScope;
import com.faststudio.v1.services.media.MediaServiceV1;
import com.faststudio.v1.services.media.MediaStore;
import com.faststudio.v1.services.playout.PlayoutServiceV1;
import com.faststudio.v1.services.rights.Rights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Keeps the edge (worker/) in step with this server, every 5 s:
 * 1. copies every finished media folder to the {@link MediaStore} (R2) once;
 * 2. publishes each channel's schedule snapshot (next ~48 h, rights pre-evaluated per territory) to KV when it changed.
 * The Worker builds every viewer's live playlist from the snapshot and the clock alone, so viewers never reach this
 * server for playlists or video, and channels keep playing from the last snapshot if this server is down.
 */
@Component
public class EdgePublisher {
    private static final Logger log = LoggerFactory.getLogger(EdgePublisher.class);
    static final long REPUBLISH_MS = 10 * 60_000;   // resend unchanged snapshots now and then (e.g. after the Worker's state was reset)

    private final Data data;
    private final MediaServiceV1 media;
    private final MediaStore store;
    private final EdgeClient edge;
    private final JsonMapper json;
    private final Set<String> stored = ConcurrentHashMap.newKeySet();
    private final Map<String, String> sent = new HashMap<>();
    private final Map<String, Long> sentAt = new HashMap<>();
    private boolean warned;

    public EdgePublisher(Data data, MediaServiceV1 media, MediaStore store, EdgeClient edge, JsonMapper json) {
        this.data = data;
        this.media = media;
        this.store = store;
        this.edge = edge;
        this.json = json;
    }

    /** True once the media folder is on the edge; snapshots and ad decisions only point viewers at stored media. */
    public boolean stored(String mediaKey) { return mediaKey != null && stored.contains(mediaKey); }

    @Scheduled(initialDelay = 3_000, fixedDelay = 5_000)
    public void tick() {
        if (!edge.configured()) {
            if (!warned) log.warn("faststudio.edge.secret (FASTSTUDIO_EDGE_SECRET) is not set: nothing is published to the edge, so live streams won't play.");
            warned = true;
            return;
        }
        try {
            syncMedia();
            publishSnapshots();
            if (warned) log.info("Edge reachable again at {}", edge.publicUrl());
            warned = false;
        } catch (IOException e) {
            if (!warned) log.warn("Edge not reachable ({}); retrying every 5 s. Is the Worker running (start.bat starts it)?", e.getMessage());
            warned = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ media

    void syncMedia() throws IOException, InterruptedException {
        List<String> keys = new ArrayList<>(List.of(MediaServiceV1.STANDBY, MediaServiceV1.GEO));
        keys.addAll(TenantScope.bypass(() -> data.db.sql("select media_key from asset where status = 'READY' and media_key is not null")
                .query(String.class).list()));
        for (String key : keys) {
            if (stored.contains(key)) continue;
            Path dir = media.mediaDir().resolve(key);
            if (!Files.exists(dir.resolve("master.m3u8"))) continue;   // still being packaged
            try {
                if (!store.has(key)) upload(key, dir);
                stored.add(key);
            } catch (IOException e) {
                if (e instanceof java.net.ConnectException || e instanceof java.net.http.HttpConnectTimeoutException) throw e;
                log.warn("Couldn't copy {} to the edge: {}", key, e.getMessage());   // retried next tick
            }
        }
    }

    private void upload(String key, Path dir) throws IOException, InterruptedException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        Path master = dir.resolve("master.m3u8");
        for (Path f : files) if (!f.equals(master)) store.put(key + "/" + rel(dir, f), f, type(f));
        store.put(key + "/master.m3u8", master, type(master));   // last: its presence means "complete"
        log.info("Copied {} ({} files) to the edge", key, files.size());
    }

    static String rel(Path dir, Path f) { return dir.relativize(f).toString().replace('\\', '/'); }

    static String type(Path f) {
        String n = f.getFileName().toString();
        return n.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : n.endsWith(".ts") ? "video/mp2t" : n.endsWith(".jpg") ? "image/jpeg" : "application/octet-stream";
    }

    // ------------------------------------------------------------------ schedule snapshots

    public record Rendition(String name, int width, int height, int bandwidth, String codecs) {}

    public record Media(String key, int segments) {}

    public record Fallback(String key, int segments, List<String> allowed, Long validFrom, Long validUntil) {}

    /** One schedule item; times in epoch ms. allowed = channel territories whose licence covers the whole item. */
    public record Item(String id, String kind, String key, int segments, long offsetMs, long start, long end,
                       List<String> allowed, String ownership, String scteOut, String scteIn) {}

    public record Snapshot(int v, String org, String channel, String status, List<String> territories, long segMs,
                           long from, long until, List<Rendition> ladder, Media standby, Media geo, Fallback fallback, List<Item> items) {}

    record ChannelRow(UUID orgId, String orgSlug, UUID channelId) {}

    void publishSnapshots() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        List<ChannelRow> rows = TenantScope.bypass(() -> data.db.sql("""
                select o.id as org_id, o.slug as org_slug, c.id as channel_id from channel c join organization o on o.id = c.org_id
                where c.status <> 'DRAFT'""").query(ChannelRow.class).list());
        for (ChannelRow r : rows) {
            Snapshot s = TenantScope.as(r.orgId(), () -> snapshot(r.orgId(), r.orgSlug(), r.channelId(), Instant.ofEpochMilli(now)));
            String key = s.org() + "/" + s.channel(), body = json.writeValueAsString(s);
            if (body.equals(sent.get(key)) && now - sentAt.getOrDefault(key, 0L) < REPUBLISH_MS) continue;
            edge.putChannel(s.org(), s.channel(), body);
            sent.put(key, body);
            sentAt.put(key, now);
        }
    }

    Snapshot snapshot(UUID org, String orgSlug, UUID channelId, Instant now) {
        Channel ch = data.channel(org, channelId);
        List<String> terr = ch.territoryList();
        // hour-aligned window: the snapshot only changes when the schedule does, or once an hour
        long hour = 3_600_000L, from = Math.floorDiv(now.toEpochMilli(), hour) * hour - hour, until = from + 49 * hour;
        Map<UUID, Asset> assets = data.assets(org);
        Map<UUID, Rights.Policy> policies = data.policies(org);
        List<Item> items = new ArrayList<>();
        if ("LIVE".equals(ch.status()) && ch.publishedVersionId() != null) {
            for (Entry e : data.entries(org, ch.publishedVersionId(), Instant.ofEpochMilli(from), Instant.ofEpochMilli(until))) {
                if (e.isBreak()) {
                    items.add(new Item(e.id().toString(), e.kind(), null, 0, 0, e.start().toEpochMilli(), e.end().toEpochMilli(), terr,
                            e.ownership(), PlayoutServiceV1.scteHex(e, true), PlayoutServiceV1.scteHex(e, false)));
                    continue;
                }
                Asset a = assets.get(e.assetId());
                String key = a != null && a.ready() && stored(a.mediaKey()) ? a.mediaKey() : null;   // null: standby plays
                List<String> ok = a == null ? List.of() : terr.stream()
                        .filter(c -> Rights.evaluate(policies.get(a.id()), terr, c, e.start(), e.end()).allowed()).toList();
                items.add(new Item(e.id().toString(), e.kind(), key, key == null ? 0 : media.segments(key), e.assetOffsetMs(),
                        e.start().toEpochMilli(), e.end().toEpochMilli(), ok, null, null, null));
            }
        }
        return new Snapshot(1, orgSlug, ch.slug(), ch.status(), terr, Times.SEG_MS, from, until, ladder(),
                platform(MediaServiceV1.STANDBY), platform(MediaServiceV1.GEO), fallback(ch, assets, policies), items);
    }

    private Media platform(String key) { return new Media(stored(key) ? key : null, stored(key) ? media.segments(key) : 0); }

    private Fallback fallback(Channel ch, Map<UUID, Asset> assets, Map<UUID, Rights.Policy> policies) {
        Asset fb = ch.fallbackAssetId() == null ? null : assets.get(ch.fallbackAssetId());
        if (fb == null || !fb.ready() || !stored(fb.mediaKey())) return null;
        Rights.Policy p = policies.get(fb.id());
        List<String> terr = ch.territoryList();
        // countries checked here; the Worker checks the licence window per segment
        List<String> ok = terr.stream().filter(c -> Rights.evaluate(p, terr, c, null, null).allowed()).toList();
        return new Fallback(fb.mediaKey(), media.segments(fb.mediaKey()), ok,
                p == null || p.validFrom() == null ? null : p.validFrom().toInstant().toEpochMilli(),
                p == null || p.validUntil() == null ? null : p.validUntil().toInstant().toEpochMilli());
    }

    private static List<Rendition> ladder() {
        return MediaServiceV1.LADDER.stream().map(r -> new Rendition(r.name(), r.width(), r.height(), r.bandwidth(), r.codecs())).toList();
    }
}
