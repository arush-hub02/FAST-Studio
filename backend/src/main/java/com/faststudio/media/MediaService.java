package com.faststudio.media;

import com.faststudio.core.TenantScope;
import com.faststudio.core.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Real media processing with ffmpeg. Every asset becomes an ABR ladder (360p / 540p / 720p H.264 + AAC) in one
 * ffmpeg pass, each rendition cut into exactly-3-second HLS segments with keyframes on every boundary, plus a
 * master playlist. Identical cadence across renditions, programmes, ads and slates is what lets the playout stitch
 * them together and lets players switch quality at any segment (HOW_FAST_WORKS §2.1).
 * Layout: media/{key}/{0,1,2}/seg_NNNNN.ts + index.m3u8, media/{key}/master.m3u8, media/{key}/thumb.jpg.
 * Media processed before the ladder existed (flat media/{key}/seg_*.ts) keeps working as a single rendition.
 * ponytail: in-process job pool; a durable job queue / media provider replaces it for production.
 */
@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    public static final String STANDBY = "_platform/standby", GEO = "_platform/geo";

    public record Rendition(String name, int width, int height, int videoKbps, String level, String codecs, int bandwidth) {}

    public static final List<Rendition> LADDER = List.of(
            new Rendition("360p", 640, 360, 600, "3.0", "avc1.4d401e,mp4a.40.2", 800_000),
            new Rendition("540p", 960, 540, 1400, "3.1", "avc1.4d401f,mp4a.40.2", 1_700_000),
            new Rendition("720p", 1280, 720, 2500, "3.1", "avc1.4d401f,mp4a.40.2", 2_900_000));

    private final JdbcClient db;
    private final Path mediaDir, uploadDir;
    private final String configuredFfmpeg;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, Integer> segmentCounts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> ladders = new ConcurrentHashMap<>();
    private volatile String ffmpeg;

    public MediaService(JdbcClient db, @Value("${faststudio.data-dir}") String dataDir, @Value("${faststudio.ffmpeg:}") String ffmpeg) {
        this.db = db;
        Path data = Path.of(dataDir).toAbsolutePath().normalize();
        this.mediaDir = data.resolve("media");
        this.uploadDir = data.resolve("uploads");
        this.configuredFfmpeg = ffmpeg;
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

    /** Number of 3 s segments (same in every rendition). */
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

    // ---------------------------------------------------------------- jobs

    record Job(UUID id, UUID orgId, String slug, String title, String kind, String color, String generator, long durationMs) {}

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void onStart() {
        if (ffmpeg() == null) {
            log.warn("ffmpeg not found: media processing disabled. Run 'npm install' in frontend/ or put ffmpeg on PATH.");
            return;
        }
        log.info("Using ffmpeg at {}", ffmpeg);
        pool.submit(() -> slate(STANDBY, "We'll be right back", "Programming resumes shortly", "0x1e1b4b", "0x4338ca", 60));
        pool.submit(() -> slate(GEO, "Not available in your region", "This programme isn't licensed where you are watching", "0x3f1d1d", "0x9f1239", 30));
        // resume jobs interrupted by a restart (system work across tenants)
        TenantScope.bypass(() -> db.sql("select id from asset where status = 'PROCESSING'").query(UUID.class).list()).forEach(this::enqueue);
    }

    /** Queues processing; inside a transaction it waits for the commit so the job can see the new row. */
    public void enqueue(UUID assetId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { submit(assetId); }
            });
        } else submit(assetId);
    }

    private void submit(UUID assetId) {
        pool.submit(() -> {
            try {
                process(assetId);
            } catch (Exception e) {
                log.error("processing failed {}", assetId, e);
                fail(assetId, "Unexpected processing error: " + e.getMessage());
            }
        });
    }

    void process(UUID assetId) throws Exception {
        // the job only knows the asset id: look it up as the system, then work inside that asset's tenant
        Job j = TenantScope.bypass(() -> db.sql("""
                select a.id, a.org_id, o.slug, a.title, a.kind, a.color, a.generator, a.duration_ms
                from asset a join organization o on o.id = a.org_id where a.id = :id""")
                .param("id", assetId).query(Job.class).single());
        if (ffmpeg() == null) {
            fail(assetId, "ffmpeg is not installed on the server, so the video could not be processed.");
            return;
        }
        String key = j.slug() + "/" + j.id();
        Path out = mediaDir.resolve(key);
        deleteDir(out);
        Files.createDirectories(out);
        forget(key);
        String err;
        if (j.generator() != null) {
            err = encode(generatedInputs(j), "1:a", textFilter(j), out, j.durationMs(), false);
        } else {
            Path src = findUpload(j.orgId(), j.id());
            if (src == null) { fail(assetId, "No uploaded file found. Upload a video file and try again."); return; }
            String fit = "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1";
            err = encode(List.of("-i", src.toString()), "0:a:0", fit, out, 0, false);
            if (err != null) {   // source without an audio track: add silence so it splices with everything else
                deleteDir(out); Files.createDirectories(out);
                err = encode(List.of("-i", src.toString(), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"), "1:a:0", fit, out, 0, true);
            }
        }
        if (err != null) { fail(assetId, "ffmpeg could not process this file: " + err); return; }
        int full = fullSegments(out.resolve("0").resolve("index.m3u8"));
        if (full == 0) { fail(assetId, "The video is shorter than 3 seconds."); return; }
        thumbnail(out, full);
        forget(key);
        TenantScope.as(j.orgId(), () -> db.sql("update asset set status='READY', status_detail=:d, duration_ms=:ms, media_key=:k, updated_at=now() where id=:id")
                .param("d", "Processed: " + full + " segments of 3 s in 3 qualities (360p, 540p, 720p) H.264/AAC")
                .param("ms", full * Times.SEG_MS).param("k", key).param("id", assetId).update());
        log.info("Asset ready: {} ({} s)", j.title(), full * 3);
    }

    private Path findUpload(UUID orgId, UUID assetId) throws IOException {
        Path dir = uploadDir(orgId, assetId);
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("source")).findFirst().orElse(null);
        }
    }

    private void fail(UUID id, String why) {
        TenantScope.bypass(() -> db.sql("update asset set status='FAILED', status_detail=:d, updated_at=now() where id=:id")
                .param("d", why).param("id", id).update());
    }

    // ---------------------------------------------------------------- ffmpeg recipes (drawn on a 1280x720 canvas)

    private List<String> generatedInputs(Job j) {
        String g = j.generator().replace("size=640x360", "size=1280x720");
        String video = g.startsWith("ad:")
                ? "color=c=" + g.substring(3).split("\\|")[0] + ":size=1280x720:rate=25"
                : g;
        String audio = g.startsWith("ad:") ? "sine=frequency=523:sample_rate=48000,volume=0.04,aformat=channel_layouts=stereo"
                : "anullsrc=r=48000:cl=stereo";
        return List.of("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio);
    }

    private String textFilter(Job j) {
        String band = "drawbox=x=0:y=552:w=iw:h=168:color=" + j.color().replace("#", "0x") + "@0.88:t=fill";
        if (j.generator().startsWith("ad:")) {
            String[] parts = j.generator().substring(3).split("\\|");
            String tagline = parts.length > 1 ? parts[1] : "";
            return text(j.title(), "(w-text_w)/2", "240", 108, "bold")
                    + "," + text(tagline, "(w-text_w)/2", "392", 48, "regular")
                    + "," + text("ADVERTISEMENT  -  fictional demo creative", "(w-text_w)/2", "636", 32, "regular");
        }
        return band + "," + text(j.title(), "48", "576", 60, "bold")
                + "," + text("FAST Studio demo programme   %{pts\\:hms}", "48", "652", 36, "regular");
    }

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

    private static String text(String s, String x, String y, int size, String weight) {
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
    private String encode(List<String> inputs, String audioMap, String base, Path out, long durationMs, boolean shortest) throws Exception {
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
            cmd.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-level", r.level(), "-pix_fmt", "yuv420p", "-r", "25",
                    "-g", "75", "-keyint_min", "75", "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*3)",
                    "-b:v", r.videoKbps() + "k", "-maxrate", (r.videoKbps() * 11 / 10) + "k", "-bufsize", (r.videoKbps() * 2) + "k",
                    "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "96k",
                    "-f", "hls", "-hls_time", "3", "-hls_playlist_type", "vod",
                    "-hls_segment_filename", dir.resolve("seg_%05d.ts").toString(), dir.resolve("index.m3u8").toString()));
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(60, TimeUnit.MINUTES)) { p.destroyForcibly(); return "timed out"; }
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

    /** Count only full 3 s segments; a trailing partial segment is never scheduled. */
    static int fullSegments(Path playlist) throws IOException {
        if (!Files.exists(playlist)) return 0;
        int n = 0;
        for (String line : Files.readAllLines(playlist)) {
            if (!line.startsWith("#EXTINF:")) continue;
            double d = Double.parseDouble(line.substring(8, line.indexOf(',') < 0 ? line.length() : line.indexOf(',')));
            if (d >= 2.9) n++; else break;
        }
        return n;
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
