package com.faststudio.v1.services.media;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ads and slate in R2 only: the server drops their video files and keeps the playlists, which still give counts and levels. */
class MediaPruneTest {
    @Test
    void pruningKeepsOnlyPlaylistsAndThumbnailAndTheCountsStillHold() throws Exception {
        Path root = Files.createTempDirectory("fast-prune");
        MediaServiceV1 media = new MediaServiceV1(null, null, root.toString(), "", List.of(), null, null);
        String key = "acme/ad.1";
        Path dir = root.resolve("media").resolve(key);
        for (String level : List.of("2", "3", "v2", "v3", "a")) {
            Files.createDirectories(dir.resolve(level));
            Files.writeString(dir.resolve(level + "/index.m3u8"), "#EXTM3U\n#EXTINF:5.0,\nseg_00000.ts\n#EXTINF:5.0,\nseg_00001.ts\n#EXT-X-ENDLIST\n");
            Files.write(dir.resolve(level + "/seg_00000.ts"), new byte[1000]);
            Files.write(dir.resolve(level + "/seg_00001.ts"), new byte[1000]);
        }
        Files.write(dir.resolve("v2/init.mp4"), new byte[100]);
        Files.writeString(dir.resolve("master.m3u8"), "#EXTM3U\n");
        Files.writeString(dir.resolve("s-master.m3u8"), "#EXTM3U\n");
        Files.write(dir.resolve("thumb.jpg"), new byte[10]);

        media.pruneToIndex(key);

        try (var s = Files.walk(dir)) {
            List<String> left = s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
            assertEquals(List.of("2/index.m3u8", "3/index.m3u8", "a/index.m3u8", "master.m3u8", "s-master.m3u8", "thumb.jpg", "v2/index.m3u8", "v3/index.m3u8"), left);
        }
        assertEquals(List.of(2, 3), media.levels(key), "levels still read from the kept playlists");
        assertEquals(2, media.segments(key), "segments counted from the playlist, not the deleted files");
        assertTrue(media.hasFmp4(key));
        assertFalse(media.hasLocalSegments(key), "only playlists left: the edge publisher fetches the pieces back to repackage");
        MediaServiceV1.deleteDir(root);
    }

    /** Channels with fMP4 platform programmes play audio from its own playlist: our media gets video-only and audio-only copies. */
    @Test
    void fmp4CopiesAreVideoOnlyPerLevelPlusOneAudioTrackWithTheSameSegments() throws Exception {
        Path root = Files.createTempDirectory("fast-split");
        MediaServiceV1 media = new MediaServiceV1(null, null, root.toString(), "", List.of(), null, null);
        org.junit.jupiter.api.Assumptions.assumeTrue(media.ffmpegAvailable(), "needs ffmpeg");
        Path out = root.resolve("media/acme/ad.2"), ts = out.resolve("0");
        Files.createDirectories(ts);
        Process p = new ProcessBuilder(media.ffmpeg(), "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=12",
                "-f", "lavfi", "-i", "sine=duration=12", "-c:v", "libx264", "-g", "125", "-keyint_min", "125", "-sc_threshold", "0", "-c:a", "aac", "-shortest",
                "-f", "hls", "-hls_time", "5", "-hls_playlist_type", "vod", "-hls_segment_filename", ts.resolve("seg_%05d.ts").toString().replace('\\', '/'),
                ts.resolve("index.m3u8").toString().replace('\\', '/')).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());

        assertTrue(media.fmp4(out));
        try (var s = Files.list(ts)) {
            long segs = s.filter(f -> f.toString().endsWith(".ts")).count();
            for (String folder : List.of("v0", "a"))
                try (var c = Files.list(out.resolve(folder))) {
                    assertEquals(segs, c.filter(f -> f.toString().endsWith(".m4s")).count(), folder + " has the same segments");
                }
        }
        String video = new String(Files.readAllBytes(out.resolve("v0/init.mp4")), java.nio.charset.StandardCharsets.ISO_8859_1);
        String audio = new String(Files.readAllBytes(out.resolve("a/init.mp4")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(video.contains("avc1") && !video.contains("mp4a"), "video-only copy");
        assertTrue(audio.contains("mp4a") && !audio.contains("avc1"), "audio-only copy");
        assertTrue(Files.exists(out.resolve(MediaServiceV1.FMP4_MASTER)));
        MediaServiceV1.deleteDir(root);
    }

    @Test
    void blackFillerIsOnePieceOfExactlyTheAskedFramesAtEveryLevelWithAudio() throws Exception {
        assertEquals(18, MediaServiceV1.blackFrames(714));
        assertEquals(1, MediaServiceV1.blackFrames(3));
        Path root = Files.createTempDirectory("fast-black");
        MediaServiceV1 media = new MediaServiceV1(null, null, root.toString(), "", List.of(), null, null);
        org.junit.jupiter.api.Assumptions.assumeTrue(media.ffmpegAvailable(), "needs ffmpeg");
        media.ensureBlack(18);
        for (int i = 0; i < 600 && !media.blackReady(18); i++) Thread.sleep(100);
        assertTrue(media.blackReady(18), "made within a minute");
        Path out = root.resolve("media").resolve(MediaServiceV1.blackKey(18));
        for (int level = 0; level < QualityLevels.ALL.size(); level++) {
            String list = Files.readString(out.resolve(level + "/index.m3u8"));
            assertTrue(list.contains("#EXTINF:0.720000,") && list.split("#EXTINF").length == 2, "level " + level + ": one 0.72 s piece: " + list);
            assertTrue(Files.exists(out.resolve("v" + level + "/seg_00000.m4s")));
        }
        assertTrue(Files.exists(out.resolve("a/seg_00000.m4s")), "silent audio track for fMP4 channels");
        MediaServiceV1.deleteDir(root);
    }
}
