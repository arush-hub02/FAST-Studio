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
    // the cadence and the set of quality levels are part of the key, so slates are re-made (under a new key) when either changes
    public static final String STANDBY = "_platform/standby-" + Times.SEG_S + "s-q" + QualityLevels.ALL.size(),
            GEO = "_platform/geo-" + Times.SEG_S + "s-q" + QualityLevels.ALL.size();

    /** The levels the current packaging run encodes (set by pack() from the project's setting; slates use every level). */
    private static final ThreadLocal<List<QualityLevels.Level>> RUN_LEVELS = new ThreadLocal<>();
    private static final List<QualityLevels.Level> LEGACY = QualityLevels.ALL.subList(0, 3);

    private final AssetRepository assets;
    private final Path mediaDir, uploadDir;
    private final String configuredFfmpeg;
    private final List<IContentSource> sources;
    private final MediaJobRepository jobs;
    private final StreamingServiceV1 playback;
    // the ffmpeg process of each worker thread, so a cancel or lost lease can kill it
    private final Map<Thread, Process> running = new ConcurrentHashMap<>();
    private final Set<Thread> stopped = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> segmentCounts = new ConcurrentHashMap<>();
    private final Map<String, List<Integer>> levelsOf = new ConcurrentHashMap<>();
    private final QualityLevels qualities;
    private volatile String ffmpeg;

    public MediaServiceV1(AssetRepository assets, MediaJobRepository jobs, @Value("${faststudio.data-dir}") String dataDir,
                          @Value("${faststudio.ffmpeg:}") String ffmpeg, List<IContentSource> sources, StreamingServiceV1 playback,
                          QualityLevels qualities) {
        this.qualities = qualities;
        this.assets = assets;
        this.playback = playback;
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

    /** The quality levels (QualityLevels.ALL indexes, lowest first) a media folder holds; empty for legacy single-rendition media. */
    public List<Integer> levels(String mediaKey) {
        if (mediaKey == null) return List.of();
        List<Integer> found = levelsOf.computeIfAbsent(mediaKey, k -> QualityLevels.ALL.stream().map(QualityLevels.Level::index)
                .filter(i -> Files.exists(mediaDir.resolve(k).resolve(i + "/index.m3u8"))).toList());
        if (found.isEmpty()) levelsOf.remove(mediaKey);   // not packaged yet: look again next time
        return found;
    }

    /** True when the media folder holds quality levels (vs. a legacy single rendition). */
    public boolean hasLadder(String mediaKey) { return !levels(mediaKey).isEmpty(); }

    /** The project's highest quality level (picture height). */
    public int maxHeight(UUID org) { return qualities.maxHeight(org); }

    /** Folder prefix of the lowest level inside the media key ("0/", or "" for legacy single-rendition media). */
    String firstLevelPath(String mediaKey) {
        List<Integer> l = levels(mediaKey);
        return l.isEmpty() ? "" : l.getFirst() + "/";
    }

    /** The playlist a player should open for this media (master for a ladder, else the single playlist). */
    public String entryPlaylist(String mediaKey) {
        return hasLadder(mediaKey) ? "master.m3u8" : "index.m3u8";
    }

    /** Number of segments (same in every rendition), read from the playlist, so it holds after the segments moved to R2 only. */
    public int segments(String mediaKey) {
        return segmentCounts.computeIfAbsent(mediaKey, k -> {
            try (Stream<String> s = Files.lines(mediaDir.resolve(k).resolve(firstLevelPath(k) + "index.m3u8"))) {
                return (int) s.filter(l -> l.startsWith("#EXTINF")).count();
            } catch (IOException e) {
                return 0;
            }
        });
    }

    private final Map<String, Integer> fullCounts = new ConcurrentHashMap<>();

    /**
     * Leading whole 5 s segments: what a loop (standby, region slate, fallback) may play. The Worker loops these on the
     * 5 s grid, so a short last piece (a 12.3 s slate ends in 2.3 s) would leave a hole in every loop and stall players.
     */
    public int fullSegments(String mediaKey) {
        return fullCounts.computeIfAbsent(mediaKey, k -> {
            try {
                return fullSegments(mediaDir.resolve(k).resolve(firstLevelPath(k) + "index.m3u8"));
            } catch (IOException | NumberFormatException e) {
                return 0;
            }
        });
    }

    /**
     * Ads and slate live in R2 only: once R2 holds a folder, the server deletes its video files and keeps only the
     * playlists and thumbnail (a few KB) as the index that segment counts, quality levels and previews read.
     * Previews fetch the deleted segments from the edge (MediaFileControllerV1 redirects there).
     */
    public void pruneToIndex(String mediaKey) throws IOException {
        Path dir = mediaDir.resolve(mediaKey);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".m3u8") && !n.equals("thumb.jpg")) Files.delete(p);
            }
        }
    }

    private void forget(String key) {
        segmentCounts.remove(key);
        fullCounts.remove(key);
        levelsOf.remove(key);
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
            backfillFmp4();
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
    /** partial: a first, single-level version of an ad server creative; the full ladder follows once it is published. */
    public record Packaged(String mediaKey, String previousKey, long durationMs, String detail, String error, boolean retry, boolean partial) {
        public Packaged(String mediaKey, String previousKey, long durationMs, String detail, String error, boolean retry) {
            this(mediaKey, previousKey, durationMs, detail, error, retry, false);
        }
        public static Packaged failed(String error, boolean retry) { return new Packaged(null, null, 0, null, error, retry); }
    }

    /**
     * Levels to make in this run. A creative from an ad server is new (no media yet): one level first, so it can air in
     * its first break (the project's highest at or below 720p, else its lowest); the full ladder comes in the next run.
     */
    public static List<QualityLevels.Level> firstLevels(ProcessingJob j, List<QualityLevels.Level> project) {
        if (!"AD".equals(j.kind()) || j.sourceRef() == null || j.mediaKey() != null || project.size() <= 1) return project;
        QualityLevels.Level pick = project.getFirst();
        for (QualityLevels.Level l : project) if (l.height() <= 720) pick = l;
        return List.of(pick);
    }

    /** After the job published p: a first version queues the full ladder. */
    public void published(UUID org, UUID assetId, Packaged p) {
        if (p.partial()) jobs.enqueue(org, assetId, "PROGRAMME");   // off the ad lane, so new creatives' first versions never wait behind it
    }

    /**
     * Packages the asset into a fresh folder, inside its project's scope. Never touches the asset row: the job publishes
     * the result under its fence, so a worker that lost its job can't replace newer media. Unexpected errors are thrown.
     */
    public Packaged pack(UUID org, UUID assetId) throws Exception {
        ProcessingJob j = assets.job(org, assetId);
        if (playback.handles(j)) return playback.resolve(org, j);   // streams from its platform: nothing to convert
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
        List<QualityLevels.Level> project = qualities.of(org), levels = firstLevels(j, project);
        RUN_LEVELS.set(levels);   // the quality levels of this run, read by encode() on this thread
        try {
            String err = source.ingest(j, out, this);
            if (err != null) { deleteDir(out); return Packaged.failed(err, false); }
            int full = fullSegments(out.resolve(levels.getFirst().index() + "/index.m3u8"));
            if (full == 0) { deleteDir(out); return Packaged.failed("The video is shorter than " + Times.SEG_S + " seconds.", false); }
            thumbnail(out, full);
            log.info("Asset packaged: {} ({} s)", j.title(), full * Times.SEG_S);
            String names = String.join(", ", levels.stream().map(QualityLevels.Level::name).toList());
            return new Packaged(key, j.mediaKey(), full * Times.SEG_MS, "Processed: " + full + " segments of " + Times.SEG_S + " s in "
                    + levels.size() + (levels.size() == 1 ? " quality" : " qualities") + " (" + names + ") H.264/AAC, loudness -23 LUFS"
                    + (levels.size() < project.size() ? "; first version, the other qualities follow" : ""), null, false, levels.size() < project.size());
        } catch (Retryable e) {
            deleteDir(out);
            return Packaged.failed(e.getMessage(), true);
        } catch (Exception e) {
            deleteDir(out);
            throw e;
        } finally {
            RUN_LEVELS.remove();
        }
    }

    /** After a publish: the replaced revision's local folder goes. */
    public void retire(Packaged p) throws IOException {
        // ponytail: the previous revision's copy on R2 is left behind; add an R2 lifecycle rule when storage matters
        if (p.previousKey() != null && !p.previousKey().equals(p.mediaKey()) && !p.previousKey().startsWith("_platform/")) discard(p.previousKey());
    }

    /** A revision that was never published (the job was cancelled or taken over). */
    public void discard(String key) throws IOException {
        if (key == null) return;   // a streaming video has no folder
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

    // ---------------------------------------------------------------- black fillers

    /** Frames per second of everything we encode (see runEncode). */
    public static final int FPS = 25;
    private final Set<Integer> blackQueued = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService blackRuns = java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().name("black-fillers").daemon().factory());

    /** Frame count of a black filler for a gap of ms (nearest frame, at least one). */
    public static int blackFrames(long ms) { return (int) Math.max(1, Math.round(ms * FPS / 1000.0)); }

    /** Key of the black filler that is the given number of frames long: one segment, every level, silent audio. */
    public static String blackKey(int frames) { return "_platform/black-" + frames + "f-q" + QualityLevels.ALL.size(); }

    /** True once the filler of this length is packaged on this server (EdgePublisher then copies it to the edge). */
    public boolean blackReady(int frames) { return Files.exists(mediaDir.resolve(blackKey(frames)).resolve(FMP4_MASTER)); }

    /**
     * Makes the filler of this length in the background, once. A streaming programme that ends between two 5 s grid
     * points leaves a gap before the next item; a filler the exact length of the gap keeps the playlist true to the
     * media (a cut-off 5 s slate piece made players play ~5 s of slate and drift behind live at every such break).
     */
    public void ensureBlack(int frames) {
        if (blackReady(frames) || ffmpeg() == null || !blackQueued.add(frames)) return;
        blackRuns.submit(() -> {
            String key = blackKey(frames);
            Path out = mediaDir.resolve(key);
            RUN_LEVELS.set(QualityLevels.ALL);
            try {
                if (Files.exists(out)) try (Stream<Path> s = Files.walk(out)) { for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
                Files.createDirectories(out);
                String err = encode(List.of("-f", "lavfi", "-i", "color=c=black:size=1280x720:rate=" + FPS, "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"),
                        "1:a", "null", out, frames * 1000L / FPS, false);
                if (err != null) log.error("black filler {} failed: {}", key, err);
                forget(key);
            } catch (Exception e) {
                log.error("black filler {} failed", key, e);
            } finally {
                RUN_LEVELS.remove();
                blackQueued.remove(frames);   // a failure may be tried again later
            }
        });
    }

    // ---------------------------------------------------------------- ffmpeg recipes (drawn on a 1280x720 canvas)

    private void slate(String key, String title, String subtitle, String c0, String c1, int seconds) {
        Path out = mediaDir.resolve(key);
        if (Files.exists(out.resolve("index.m3u8")) || Files.exists(out.resolve("master.m3u8"))) return;   // legacy or ladder
        RUN_LEVELS.set(QualityLevels.ALL);   // slates serve every tenant, so they carry every level
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
        } finally {
            RUN_LEVELS.remove();
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
        if (err == null) {
            writeMaster(out);
            fmp4(out);
        }
        return err;
    }

    /**
     * One decode + filter pass, split into the run's quality levels (folder = level index), each encoded with the same
     * keyframe/segment cadence. ponytail: a small source is scaled up to every chosen level; cap at the source size if encode time matters.
     */
    private String runEncode(List<String> inputs, String audioMap, String base, Path out, long durationMs, boolean shortest) throws Exception {
        List<QualityLevels.Level> ladder = RUN_LEVELS.get() == null ? LEGACY : RUN_LEVELS.get();
        List<String> cmd = new ArrayList<>(List.of(ffmpeg(), "-y", "-hide_banner", "-loglevel", "error"));
        cmd.addAll(inputs);
        StringBuilder fc = new StringBuilder("[0:v]").append(base).append(",split=").append(ladder.size());
        for (int i = 0; i < ladder.size(); i++) fc.append("[s").append(i).append(']');
        for (int i = 0; i < ladder.size(); i++)
            fc.append(";[s").append(i).append("]scale=").append(ladder.get(i).width()).append(':').append(ladder.get(i).height()).append("[v").append(i).append(']');
        cmd.addAll(List.of("-filter_complex", fc.toString()));
        for (int i = 0; i < ladder.size(); i++) {
            QualityLevels.Level r = ladder.get(i);
            Path dir = Files.createDirectories(out.resolve(String.valueOf(r.index())));
            cmd.addAll(List.of("-map", "[v" + i + "]", "-map", audioMap));
            if (durationMs > 0) cmd.addAll(List.of("-t", String.valueOf(durationMs / 1000.0)));
            if (shortest) cmd.add("-shortest");
            String gop = String.valueOf(25 * Times.SEG_S);   // 25 fps: exactly one keyframe per segment
            cmd.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-level", r.h264Level(), "-pix_fmt", "yuv420p", "-r", "25",
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

    // ---------------------------------------------------------------- fMP4 copies

    /** Written last in a media folder once its fMP4 copy (f0/, f1/, ...) is complete. */
    public static final String FMP4_MASTER = "s-master.m3u8";

    public boolean hasFmp4(String mediaKey) { return mediaKey != null && Files.exists(mediaDir.resolve(mediaKey).resolve(FMP4_MASTER)); }

    /**
     * An fMP4 (CMAF) copy of every rendition, next to the MPEG-TS one: f{v}/init.mp4 + seg_NNNNN.m4s. Needed on channels
     * whose library programmes stream as fMP4, because HLS can't switch from fMP4 back to TS inside one playlist. A
     * repackage, not a re-encode: segments already start on keyframes every 5 s, so the boundaries are the same.
     * Failures only log: the channel then plays slate instead of this media. Returns true when the copy is complete.
     */
    public boolean fmp4(Path out) {
        List<QualityLevels.Level> present = present(out);
        if (ffmpeg() == null || present.isEmpty()) return false;
        try {
            // fMP4 platforms (Mux) deliver audio as its own track, so channels with them play audio from a separate
            // playlist: our media follows with video-only copies per level (v{level}/) and one audio-only copy (a/)
            long segments = count(out.resolve(String.valueOf(present.getFirst().index())), ".ts");
            for (QualityLevels.Level level : present)
                if (!repackage(out, level.index(), "v" + level.index(), "0:v:0", segments)) return false;
            if (!repackage(out, present.getFirst().index(), "a", "0:a:0", segments)) return false;
            Files.writeString(out.resolve(FMP4_MASTER), masterPlaylist(present, i -> "v" + i + "/index.m3u8"));
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("fMP4 copy of {} failed: {}", out, e.toString());
            return false;
        }
    }

    /** One fMP4 track of the MPEG-TS level {@code from} into {@code folder}: a repackage, segment for segment (no re-encode). */
    private boolean repackage(Path out, int from, String folder, String map, long segments) throws Exception {
        Path src = out.resolve(from + "/index.m3u8"), dir = out.resolve(folder);
        deleteDir(dir);
        Files.createDirectories(dir);
        // audio frames don't end exactly where the video does: cut the audio at the video's length, or a sliver of a
        // last piece makes the audio track one segment longer than the video
        double seconds = 0;
        for (String line : Files.readAllLines(src)) if (line.startsWith("#EXTINF:")) seconds += Double.parseDouble(line.substring(8).split(",")[0]);
        Process p = new ProcessBuilder(ffmpeg(), "-y", "-hide_banner", "-loglevel", "error", "-i", src.toString(), "-map", map, "-c", "copy",
                "-bsf:a", "aac_adtstoasc",   // MPEG-TS carries ADTS audio; MP4 needs the raw form
                "-t", String.format(Locale.ROOT, "%.3f", seconds),
                "-f", "hls", "-hls_time", String.valueOf(Times.SEG_S), "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
                // forward slashes: ffmpeg finds the playlist's folder (where init.mp4 goes) only by '/'
                "-hls_segment_filename", slash(dir.resolve("seg_%05d.m4s")), slash(dir.resolve("index.m3u8")))
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) { log.warn("fMP4 copy {} of {} failed: {}", folder, out, output); return false; }
        if (count(dir, ".m4s") != segments || !Files.exists(dir.resolve("init.mp4"))) {
            log.warn("fMP4 copy {} of {} has different segment boundaries; not used", folder, out);
            return false;
        }
        return true;
    }

    private static String slash(Path p) { return p.toAbsolutePath().toString().replace('\\', '/'); }

    private static long count(Path dir, String ext) throws IOException {
        try (Stream<Path> s = Files.list(dir)) { return s.filter(p -> p.getFileName().toString().endsWith(ext)).count(); }
    }

    /** True when the folder still has its MPEG-TS pieces on disk (ads and slate keep only playlists once R2 has them). */
    public boolean hasLocalSegments(String mediaKey) {
        Path dir = mediaDir.resolve(mediaKey);
        List<QualityLevels.Level> p = present(dir);
        try {
            return !p.isEmpty() && count(dir.resolve(String.valueOf(p.getFirst().index())), ".ts") > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Media packaged before the current fMP4 layout gets its copies at start-up (the edge uploads them on its next
     * pass). Folders whose pieces live in R2 only are done by the edge publisher, which fetches them back first.
     */
    void backfillFmp4() {
        if (!Files.isDirectory(mediaDir)) return;
        try (Stream<Path> s = Files.walk(mediaDir, 2)) {
            for (Path dir : s.filter(d -> Files.exists(d.resolve("master.m3u8")) && !present(d).isEmpty() && !Files.exists(d.resolve(FMP4_MASTER))
                    && hasLocalSegments(mediaDir.relativize(d).toString().replace('\\', '/'))).toList())
                if (fmp4(dir)) log.info("Made the fMP4 copy of {}", mediaDir.relativize(dir));
        } catch (IOException e) {
            log.warn("fMP4 backfill stopped: {}", e.toString());
        }
    }

    /** The quality levels a media folder holds (folders "0" … "5" with a playlist), lowest first. */
    static List<QualityLevels.Level> present(Path out) {
        return QualityLevels.ALL.stream().filter(l -> Files.exists(out.resolve(l.index() + "/index.m3u8"))).toList();
    }

    /** VOD master playlist of the levels the folder holds (used by the Library preview and by SGAI ad assets). */
    private static void writeMaster(Path out) throws IOException {
        Files.writeString(out.resolve("master.m3u8"), masterPlaylist(present(out), i -> i + "/index.m3u8"));
    }

    public static String masterPlaylist(List<QualityLevels.Level> levels, java.util.function.IntFunction<String> variantUri) {
        StringBuilder m = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        for (QualityLevels.Level r : levels) {
            m.append(String.format(Locale.ROOT, "#EXT-X-STREAM-INF:BANDWIDTH=%d,AVERAGE-BANDWIDTH=%d,RESOLUTION=%dx%d,CODECS=\"%s\",FRAME-RATE=25.000,NAME=\"%s\"\n",
                    r.bandwidth(), r.bandwidth() * 85 / 100, r.width(), r.height(), r.codecs(), r.name()));
            m.append(variantUri.apply(r.index())).append('\n');
        }
        return m.toString();
    }

    private void thumbnail(Path out, int segments) throws Exception {
        String seg = String.format("seg_%05d.ts", Math.min(1, segments - 1));
        Process p = new ProcessBuilder(ffmpeg(), "-y", "-loglevel", "error", "-i", out.resolve(String.valueOf(present(out).getLast().index())).resolve(seg).toString(),
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
