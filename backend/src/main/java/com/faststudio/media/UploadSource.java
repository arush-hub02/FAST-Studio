package com.faststudio.media;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Content connector for files uploaded through FAST Studio (data/uploads/{org}/{asset}/source.*). */
@Component
public class UploadSource implements ContentSource {
    static final String FIT = "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1";

    @Override
    public boolean handles(MediaService.Job job) { return job.generator() == null && job.sourceUrl() == null && job.sourceRef() == null; }

    @Override
    public String ingest(MediaService.Job j, Path out, MediaService packager) throws Exception {
        Path src = findUpload(packager.uploadDir(j.orgId(), j.id()));
        if (src == null) return "No uploaded file found. Upload a video file and try again.";
        String err = packager.encode(List.of("-i", src.toString()), "0:a:0", FIT, out, 0, false);
        if (err != null) {   // source without an audio track: add silence so it splices with everything else
            MediaService.deleteDir(out);
            Files.createDirectories(out);
            err = packager.encode(List.of("-i", src.toString(), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"), "1:a:0", FIT, out, 0, true);
        }
        return err == null ? null : "ffmpeg could not process this file: " + err;
    }

    private static Path findUpload(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("source")).findFirst().orElse(null);
        }
    }
}
