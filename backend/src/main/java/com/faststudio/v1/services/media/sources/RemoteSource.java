package com.faststudio.v1.services.media.sources;

import com.faststudio.v1.dto.media.AssetDto.ProcessingJob;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.PublicUrl;
import com.faststudio.v1.services.media.MediaServiceV1;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Content connector for media fetched from a public URL (asset.source_url), e.g. a creative an ad server returned. */
@Component
public class RemoteSource implements IContentSource {
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public boolean handles(ProcessingJob job) { return job.sourceUrl() != null; }

    @Override
    public String ingest(ProcessingJob j, Path out, MediaServiceV1 packager) throws Exception {
        String url;
        try {
            // ffmpeg downloads by itself and would follow redirects unchecked: resolve them here first, every hop checked.
            // ponytail: the final server could still redirect ffmpeg; download to a temp file first if that matters.
            url = PublicUrl.resolve(HTTP, j.sourceUrl(), Duration.ofSeconds(15)).toString();
        } catch (ApiException e) {
            return e.getMessage();
        } catch (IOException e) {
            throw new MediaServiceV1.Retryable("The remote video could not be reached. It will be tried again; verify the address if this repeats.");
        }
        String err = encodeUrl(url, out, packager);
        return err == null ? null : "The remote video could not be processed. Verify the source and try again.";
    }

    /** Packages the video at a public http(s) URL; null on success, else ffmpeg's last error line. */
    static String encodeUrl(String url, Path out, MediaServiceV1 packager) throws Exception {
        List<String> in = List.of("-protocol_whitelist", "http,https,tcp,tls,crypto", "-i", url);
        String err = packager.encode(in, "0:a:0", UploadSource.FIT, out, 0, false);
        if (err != null) {   // no audio track: add silence so it splices with everything else
            MediaServiceV1.deleteDir(out);
            Files.createDirectories(out);
            err = packager.encode(List.of("-protocol_whitelist", "http,https,tcp,tls,crypto", "-i", url, "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"),
                    "1:a:0", UploadSource.FIT, out, 0, true);
        }
        return err;
    }
}
