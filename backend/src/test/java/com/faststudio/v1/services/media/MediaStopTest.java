package com.faststudio.v1.services.media;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A cancelled or lost job kills its worker's running ffmpeg, and that worker starts no further encode until released. */
class MediaStopTest {
    @Test
    void stopKillsTheRunningEncode() throws Exception {
        Path dir = Files.createTempDirectory("fast-stop");
        MediaServiceV1 media = new MediaServiceV1(null, null, dir.toString(), "", List.of());
        assumeTrue(media.ffmpegAvailable(), "needs ffmpeg (frontend/node_modules/ffmpeg-static or PATH)");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<Thread> thread = new CompletableFuture<>();
        Future<String> encode = worker.submit(() -> {
            thread.complete(Thread.currentThread());
            return media.encode(List.of("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"),
                    "1:a", "null", dir.resolve("out"), 600_000, false);   // ten minutes of video: far longer than the test
        });
        Thread.sleep(1_500);
        long t0 = System.nanoTime();
        media.stop(thread.get());
        assertEquals("stopped", encode.get(15, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(10), "killed, not run to the end");
        assertEquals("stopped", worker.submit(() -> media.encode(List.of("-f", "lavfi", "-i", "testsrc2"), "0:a", "null", dir.resolve("again"), 1000, false))
                .get(5, TimeUnit.SECONDS), "a stopped worker refuses the next encode");
        media.release(thread.get());
        worker.shutdownNow();
        MediaServiceV1.deleteDir(dir);
    }
}
