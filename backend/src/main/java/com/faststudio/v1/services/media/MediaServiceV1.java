package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.dto.media.AssetDto.ProcessingJob;
import com.faststudio.v1.repositories.media.AssetRepository;
import com.faststudio.v1.repositories.media.MediaJobRepository;
import com.faststudio.v1.services.media.sources.IContentSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Real media processing with ffmpeg. Every asset becomes an ABR ladder (360p / 540p / 720p H.264 + AAC) in one
 * ffmpeg pass, each rendition cut into exactly-5-second HLS segments with keyframes on every boundary and audio
 * normalised to EBU R128 (-23 LUFS), plus a master playlist. Identical cadence and loudness across renditions,
 * programmes, ads and slates is what lets the playout stitch them together and lets players switch quality at any
 * segment (HOW_FAST_WORKS §2.1). Where the video comes from is a {@link IContentSource} connector.
 * Layout: media/{key}/{0,1,2}/seg_NNNNN.ts + index.m3u8, media/{key}/master.m3u8, media/{key}/thumb.jpg, where
 * key = {org slug}/{asset id}.{packaging time}: every packaging run gets a fresh key, so the edge can cache
 * segments forever. EdgePublisher copies finished folders to R2.
 * Packaging runs as durable jobs (media_job) claimed by {@link com.faststudio.messaging.v1.media.MediaJobObserverV1}.
 */
@Service
public class MediaServiceV1 {
    private static final Logger log = LoggerFactory.getLogger(MediaServiceV1.class);
    // the cadence is part of the key, so slates are re-made (under a new key) whenever it changes
    public static final String STANDBY = "_platform/standby-" + Times.SEG_S + "s", GEO = "_platform/geo-" + Times.SEG_S + "s";

    public record Rendition(String name, int width, int height, int videoKbps, String level, String codecs, int bandwidth) {}

    public static final List<Rendition> LADDER = List.of(
            new Rendition("360p", 640, 360, 600, "3.0", "avc1.4d401e,mp4a.40.2", 800_000),
            new Rendition("540p", 960, 540, 1400, "3.1", "avc1.4d401f,mp4a.40.2", 1_700_000),
            new Rendition("720p", 1280, 720, 2500, "3.1", "avc1.4d401f,mp4a.40.2", 2_900_000));

    private final AssetRepository assets;
    private final Path mediaDir, uploadDir;
    private final String configuredFfmpeg;
    private final List<IContentSource> sources;
    private final MediaJobRepository jobs;
    // the ffmpeg process of each worker thread, so a cancel or lost lease can kill it
    private final Map<Thread, Process> running = new ConcurrentHashMap<>();
    private final Set<Thread> stopped = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> segmentCounts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> ladders = new ConcurrentHashMap<>();
    private volatile String ffmpeg;

    public MediaServiceV1(AssetRepository assets, MediaJobRepository jobs, @Value("${faststudio.data-dir}") String dataDir,
                          @Value("${faststudio.ffmpeg:}") String ffmpeg, List<IContentSource> sources) {
        this.assets = assets;
        this.jobs = jobs;
        Path data = Path.of(dataDir).toAbsolutePath().normalize();
        this.mediaDir = data.resolve("media");
        this.uploadDir = data.resolve("uploads");
        this.configuredFfmpeg = ffmpeg;
        this.sources = sources;
    }

    public Path mediaDir() { return mediaDir; }

    public Path uploadDir(UUID orgId, UUID assetId) { return uploadDir.resolve(orgId.toString()).resolve(assetId.toString()); }

    public boolean ffmpegAvailable() { return ffmpeg() != null; }

    String ffmpeg() {
        if (ffmpeg != null) return ffmpeg;
        if ("disabled".equals(configuredFfmpeg)) return null;          // tests
        List<String> candidates = new ArrayList<>();
        if (configuredFfmpeg != null && !configuredFfmpeg.isBlank()) candidates.add(configuredFfmpeg);
        candidates.add("ffmpeg");
        for (String base : List.of("../frontend/node_modules/ffmpeg-static/", "frontend/node_modules/ffmpeg-static/"))
            for (String exe : List.of("ffmpeg.exe", "ffmpeg")) candidates.add(Path.of(base, exe).toAbsolutePath().toString());
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return ffmpeg = c;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- layout helpers (used by playout and API)

    /** True when the media folder holds the 3-rendition ladder (vs. a legacy single rendition). */
    public boolean hasLadder(String mediaKey) {
        return ladders.computeIfAbsent(mediaKey, k -> Files.isDirectory(mediaDir.resolve(k).resolve("0")));
    }

    /** Folder prefix of rendition v inside the media key ("2/" or "" for legacy single-rendition media). */
    public String variantPath(String mediaKey, int v) {
        return hasLadder(mediaKey) ? Math.max(0, Math.min(v, LADDER.size() - 1)) + "/" : "";
    }

    /** The playlist a player should open for this media (master for a ladder, else the single playlist). */
    public String entryPlaylist(String mediaKey) {
        return hasLadder(mediaKey) ? "master.m3u8" : "index.m3u8";
    }

    /** Number of segments (same in every rendition). */
    public int segments(String mediaKey) {
        return segmentCounts.computeIfAbsent(mediaKey, k -> {
            try (Stream<Path> s = Files.list(mediaDir.resolve(k).resolve(variantPath(k, 0)))) {
                return (int) s.filter(p -> p.getFileName().toString().endsWith(".ts")).count();
            } catch (IOException e) {
                return 0;
            }
        });
    }

    private void forget(String key) {
        segmentCounts.remove(key);
        ladders.remove(key);
    }

    // ---------------------------------------------------------------- jobs (durable rows in media_job, run by MediaJobObserverV1)

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void onStart() {
        if (ffmpeg() == null) {
            log.warn("ffmpeg not found: media processing disabled. Run 'npm install' in frontend/ or put ffmpeg on PATH.");
            return;
        }
        log.info("Using ffmpeg at {}", ffmpeg);
        // slates are idempotent start-up work (they check their own files), not jobs
        Thread.ofPlatform().name("slates").start(() -> {
            slate(STANDBY, "We'll be right back", "Programming resumes shortly", "0x1e1b4b", "0x4338ca", 60);
            slate(GEO, "Not available in your region", "This programme isn't licensed where you are watching", "0x3f1d1d", "0x9f1239", 30);
        });
    }

    /** Queues packaging in the caller's transaction (the job exists exactly when the change commits); duplicates are no-ops. */
    public void enqueue(UUID org, UUID assetId) { jobs.enqueue(org, assetId, "PROGRAMME"); }

    /** Conditions an ad creative on its own lane, ahead of any programme packaging. */
    public void enqueueCreative(UUID org, UUID assetId) { jobs.enqueue(org, assetId, "AD"); }

    /** Thrown by a content source when trying again later can help (network, provider not ready). The message is shown to the tenant. */
    public static class Retryable extends Exception {
        public Retryable(String message) { super(message); }
    }

    /** What packaging produced: a new revision for the job to publish, or why not and whether a retry can help. */
    public record Packaged(String mediaKey, String previousKey, long durationMs, String detail, String error, boolean retry) {
        public static Packaged failed(String error, boolean retry) { return new Packaged(null, null, 0, null, error, retry); }
    }

    /**
     * Packages the asset into a fresh folder, inside its project's scope. Never touches the asset row: the job publishes
     * the result under its fence, so a worker that lost its job can't replace newer media. Unexpected errors are thrown.
     */
    public Packaged pack(UUID org, UUID assetId) throws Exception {
        ProcessingJob j = assets.job(org, assetId);
        if (ffmpeg() == null) return Packaged.failed("ffmpeg is not installed on the server, so the video could not be processed.", false);
        IContentSource source = sources.stream().filter(s -> s.handles(j)).findFirst().orElse(null);
        if (source == null) return Packaged.failed("No content connector can supply this video.", false);
        String key = j.slug() + "/" + j.id() + "." + Instant.now().getEpochSecond();
        Path out = mediaDir.resolve(key);
        if (Files.isDirectory(out.getParent())) {   // half-written folders of runs that died
            try (Stream<Path> s = Files.list(out.getParent())) {
                for (Path p : s.filter(p -> p.getFileName().toString().startsWith(j.id() + ".")).toList())
                    if (!mediaDir.resolve(String.valueOf(j.mediaKey())).equals(p)) deleteDir(p);
            }
        }
        Files.createDirectories(out);
        try {
            String err = source.ingest(j, out, this);
            if (err != null) { deleteDir(out); return Packaged.failed(err, false); }
            int full = fullSegments(out.resolve("0").resolve("index.m3u8"));
            if (full == 0) { deleteDir(out); return Packaged.failed("The video is shorter than " + Times.SEG_S + " seconds.", false); }
            thumbnail(out, full);
            log.info("Asset packaged: {} ({} s)", j.title(), full * Times.SEG_S);
            return new Packaged(key, j.mediaKey(), full * Times.SEG_MS,
                    "Processed: " + full + " segments of " + Times.SEG_S + " s in 3 qualities (360p, 540p, 720p) H.264/AAC, loudness -23 LUFS", null, false);
        } catch (Retryable e) {
            deleteDir(out);
            return Packaged.failed(e.getMessage(), true);
        } catch (Exception e) {
            deleteDir(out);
            throw e;
        }
    }

    /** After a publish: the replaced revision's local folder goes. */
    public void retire(Packaged p) throws IOException {
        // ponytail: the previous revision's copy on R2 is left behind; add an R2 lifecycle rule when storage matters
        if (p.previousKey() != null && !p.previousKey().equals(p.mediaKey()) && !p.previousKey().startsWith("_platform/")) discard(p.previousKey());
    }

    /** A revision that was never published (the job was cancelled or taken over). */
    public void discard(String key) throws IOException {
        deleteDir(mediaDir.resolve(key));
        forget(key);
    }

    /** Kills the ffmpeg run of a worker thread whose job was cancelled or lost; later encodes on it refuse until {@link #release}. */
    public void stop(Thread worker) {
        stopped.add(worker);
        Process p = running.get(worker);
        if (p != null) p.destroyForcibly();
    }

    public void release(Thread worker) { stopped.remove(worker); }

    // ---------------------------------------------------------------- ffmpeg recipes (drawn on a 1280x720 canvas)

    private void slate(String key, String title, String subtitle, String c0, String c1, int seconds) {
        Path out = mediaDir.resolve(key);
        if (Files.exists(out.resolve("index.m3u8")) || Files.exists(out.resolve("master.m3u8"))) return;   // legacy or ladder
        try {
            Files.createDirectories(out);
            String err = encode(List.of("-f", "lavfi", "-i", "gradients=size=1280x720:rate=25:speed=0.004:c0=" + c0 + ":c1=" + c1,
                            "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"), "1:a",
                    text(title, "(w-text_w)/2", "280", 80, "bold") + "," + text(subtitle, "(w-text_w)/2", "400", 40, "regular"),
                    out, seconds * 1000L, false);
            if (err != null) log.error("slate {} failed: {}", key, err);
            forget(key);
        } catch (Exception e) {
            log.error("slate {} failed", key, e);
        }
    }

    public static String text(String s, String x, String y, int size, String weight) {
        // user titles: neutralise drawtext metacharacters; the %{pts} template below is trusted code
        String safe = s.contains("%{") ? s : s.replace("\\", "").replace("'", "\u2019").replace(":", "\u2236").replace("%", "");
        String font = font(weight);
        return "drawtext=" + (font == null ? "" : "fontfile='" + font + "':") + "text='" + safe + "':x=" + x + ":y=" + y
                + ":fontsize=" + size + ":fontcolor=white";
    }

    private static String font(String weight) {
        String file = weight.equals("bold") ? "segoeuib.ttf" : "segoeui.ttf";
        for (String dir : List.of("C:/Windows/Fonts/", "/usr/share/fonts/truetype/dejavu/", "/System/Library/Fonts/Supplemental/")) {
            for (String f : List.of(file, weight.equals("bold") ? "arialbd.ttf" : "arial.ttf",
                    weight.equals("bold") ? "DejaVuSans-Bold.ttf" : "DejaVuSans.ttf", weight.equals("bold") ? "Arial Bold.ttf" : "Arial.ttf"))
                if (Files.exists(Path.of(dir, f))) return (dir + f).replace(":", "\\:");
        }
        return null;
    }

    /** Returns null on success, otherwise the last ffmpeg error line. Retries once without text if drawtext is unsupported. */
    public String encode(List<String> inputs, String audioMap, String base, Path out, long durationMs, boolean shortest) throws Exception {
        String err = runEncode(inputs, audioMap, base, out, durationMs, shortest);
        if (err != null && base.contains("drawtext")) {
            String plain = Arrays.stream(base.split(",(?=drawtext|drawbox|scale|pad|setsar)"))
                    .filter(f -> !f.startsWith("drawtext")).reduce((a, b) -> a + "," + b).orElse("null");
            err = runEncode(inputs, audioMap, plain, out, durationMs, shortest);
        }
        if (err == null) writeMaster(out);
        return err;
    }

    /** One decode + filter pass, split into the three renditions, each encoded with the same keyframe/segment cadence. */
    private String runEncode(List<String> inputs, String audioMap, String base, Path out, long durationMs, boolean shortest) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg(), "-y", "-hide_banner", "-loglevel", "error"));
        cmd.addAll(inputs);
        StringBuilder fc = new StringBuilder("[0:v]").append(base).append(",split=").append(LADDER.size());
        for (int i = 0; i < LADDER.size(); i++) fc.append("[s").append(i).append(']');
        for (int i = 0; i < LADDER.size(); i++)
            fc.append(";[s").append(i).append("]scale=").append(LADDER.get(i).width()).append(':').append(LADDER.get(i).height()).append("[v").append(i).append(']');
        cmd.addAll(List.of("-filter_complex", fc.toString()));
        for (int i = 0; i < LADDER.size(); i++) {
            Rendition r = LADDER.get(i);
            Path dir = Files.createDirectories(out.resolve(String.valueOf(i)));
            cmd.addAll(List.of("-map", "[v" + i + "]", "-map", audioMap));
            if (durationMs > 0) cmd.addAll(List.of("-t", String.valueOf(durationMs / 1000.0)));
            if (shortest) cmd.add("-shortest");
            String gop = String.valueOf(25 * Times.SEG_S);   // 25 fps: exactly one keyframe per segment
            cmd.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-level", r.level(), "-pix_fmt", "yuv420p", "-r", "25",
                    "-g", gop, "-keyint_min", gop, "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*" + Times.SEG_S + ")",
                    "-b:v", r.videoKbps() + "k", "-maxrate", (r.videoKbps() * 11 / 10) + "k", "-bufsize", (r.videoKbps() * 2) + "k",
                    // EBU R128: every programme, ad and slate at the same loudness, so joins don't jump in volume
                    "-af", "loudnorm=I=-23:LRA=7:TP=-2",
                    "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "96k",
                    "-f", "hls", "-hls_time", String.valueOf(Times.SEG_S), "-hls_playlist_type", "vod",
                    "-hls_segment_filename", dir.resolve("seg_%05d.ts").toString(), dir.resolve("index.m3u8").toString()));
        }
        Thread me = Thread.currentThread();
        if (stopped.contains(me)) return "stopped";
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        running.put(me, p);
        String output;
        try {
            if (stopped.contains(me)) p.destroyForcibly();   // stopped while it was starting
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(60, TimeUnit.MINUTES)) { p.destroyForcibly(); return "timed out"; }
        } finally {
            running.remove(me);
        }
        if (stopped.contains(me)) return "stopped";
        if (p.exitValue() == 0) return null;
        String[] lines = output.split("\\R");
        return lines.length == 0 ? "exit " + p.exitValue() : lines[lines.length - 1];
    }

    /** VOD master playlist (used by the Library preview and by SGAI ad assets). */
    private static void writeMaster(Path out) throws IOException {
        Files.writeString(out.resolve("master.m3u8"), masterPlaylist(i -> i + "/index.m3u8"));
    }

    public static String masterPlaylist(java.util.function.IntFunction<String> variantUri) {
        StringBuilder m = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        for (int i = 0; i < LADDER.size(); i++) {
            Rendition r = LADDER.get(i);
            m.append(String.format(Locale.ROOT, "#EXT-X-STREAM-INF:BANDWIDTH=%d,AVERAGE-BANDWIDTH=%d,RESOLUTION=%dx%d,CODECS=\"%s\",FRAME-RATE=25.000,NAME=\"%s\"\n",
                    r.bandwidth(), r.bandwidth() * 85 / 100, r.width(), r.height(), r.codecs(), r.name()));
            m.append(variantUri.apply(i)).append('\n');
        }
        return m.toString();
    }

    private void thumbnail(Path out, int segments) throws Exception {
        String seg = String.format("seg_%05d.ts", Math.min(1, segments - 1));
        Process p = new ProcessBuilder(ffmpeg(), "-y", "-loglevel", "error", "-i", out.resolve(String.valueOf(LADDER.size() - 1)).resolve(seg).toString(),
                "-frames:v", "1", "-vf", "scale=480:270", out.resolve("thumb.jpg").toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(60, TimeUnit.SECONDS);
    }

    /** Count only full segments; a trailing partial segment is never scheduled. */
    static int fullSegments(Path playlist) throws IOException {
        if (!Files.exists(playlist)) return 0;
        int n = 0;
        for (String line : Files.readAllLines(playlist)) {
            if (!line.startsWith("#EXTINF:")) continue;
            double d = Double.parseDouble(line.substring(8, line.indexOf(',') < 0 ? line.length() : line.indexOf(',')));
            if (d >= Times.SEG_S - 0.1) n++; else break;
        }
        return n;
    }

    public static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
