package com.faststudio.media;

import com.faststudio.core.ApiException;
import com.faststudio.core.PublicUrl;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Content connector for media fetched from a public URL (asset.source_url), e.g. a creative an ad server returned. */
@Component
public class RemoteSource implements ContentSource {
    @Override
    public boolean handles(MediaService.Job job) { return job.sourceUrl() != null; }

    @Override
    public String ingest(MediaService.Job j, Path out, MediaService packager) throws Exception {
        try {
            PublicUrl.check(j.sourceUrl());
        } catch (ApiException e) {
            return e.getMessage();
        }
        String err = encodeUrl(j.sourceUrl(), out, packager);
        return err == null ? null : "ffmpeg could not process " + j.sourceUrl() + ": " + err;
    }

    /** Packages the video at a public http(s) URL; null on success, else ffmpeg's last error line. */
    static String encodeUrl(String url, Path out, MediaService packager) throws Exception {
        List<String> in = List.of("-protocol_whitelist", "http,https,tcp,tls,crypto", "-i", url);
        String err = packager.encode(in, "0:a:0", UploadSource.FIT, out, 0, false);
        if (err != null) {   // no audio track: add silence so it splices with everything else
            MediaService.deleteDir(out);
            Files.createDirectories(out);
            err = packager.encode(List.of("-protocol_whitelist", "http,https,tcp,tls,crypto", "-i", url, "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"),
                    "1:a:0", UploadSource.FIT, out, 0, true);
        }
        return err;
    }
}
