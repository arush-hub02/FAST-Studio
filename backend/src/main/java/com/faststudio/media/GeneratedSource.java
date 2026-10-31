package com.faststudio.media;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

import static com.faststudio.media.MediaService.text;

/** Content connector for demo videos and ad creatives drawn by ffmpeg (asset.generator holds the recipe). */
@Component
public class GeneratedSource implements ContentSource {
    @Override
    public boolean handles(MediaService.Job job) { return job.generator() != null; }

    @Override
    public String ingest(MediaService.Job j, Path out, MediaService packager) throws Exception {
        String err = packager.encode(inputs(j), "1:a", filter(j), out, j.durationMs(), false);
        return err == null ? null : "ffmpeg could not process this file: " + err;
    }

    private static List<String> inputs(MediaService.Job j) {
        String g = j.generator().replace("size=640x360", "size=1280x720");
        String video = g.startsWith("ad:")
                ? "color=c=" + g.substring(3).split("\\|")[0] + ":size=1280x720:rate=25"
                : g;
        String audio = g.startsWith("ad:") ? "sine=frequency=523:sample_rate=48000,volume=0.04,aformat=channel_layouts=stereo"
                : "anullsrc=r=48000:cl=stereo";
        return List.of("-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio);
    }

    private static String filter(MediaService.Job j) {
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
}
