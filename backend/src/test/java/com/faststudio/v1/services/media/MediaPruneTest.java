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
        for (String level : List.of("2", "3", "f2", "f3")) {
            Files.createDirectories(dir.resolve(level));
            Files.writeString(dir.resolve(level + "/index.m3u8"), "#EXTM3U\n#EXTINF:5.0,\nseg_00000.ts\n#EXTINF:5.0,\nseg_00001.ts\n#EXT-X-ENDLIST\n");
            Files.write(dir.resolve(level + "/seg_00000.ts"), new byte[1000]);
            Files.write(dir.resolve(level + "/seg_00001.ts"), new byte[1000]);
        }
        Files.write(dir.resolve("f2/init.mp4"), new byte[100]);
        Files.writeString(dir.resolve("master.m3u8"), "#EXTM3U\n");
        Files.writeString(dir.resolve("f-master.m3u8"), "#EXTM3U\n");
        Files.write(dir.resolve("thumb.jpg"), new byte[10]);

        media.pruneToIndex(key);

        try (var s = Files.walk(dir)) {
            List<String> left = s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
            assertEquals(List.of("2/index.m3u8", "3/index.m3u8", "f-master.m3u8", "f2/index.m3u8", "f3/index.m3u8", "master.m3u8", "thumb.jpg"), left);
        }
        assertEquals(List.of(2, 3), media.levels(key), "levels still read from the kept playlists");
        assertEquals(2, media.segments(key), "segments counted from the playlist, not the deleted files");
        assertTrue(media.hasFmp4(key));
        MediaServiceV1.deleteDir(root);
    }
}
