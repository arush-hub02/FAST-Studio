package com.faststudio.media;

import java.nio.file.Path;

/**
 * Content connector: where an asset's video comes from (an upload, a generated demo clip, later Mux and others).
 * A connector only brings the video in. Packaging into uniform 5 s segments is always ours, so playout, ad insertion
 * and delivery never depend on which connector supplied the file.
 */
public interface ContentSource {
    boolean handles(MediaService.Job job);

    /** Packages the asset into {@code out} via {@link MediaService#encode}. Returns null on success, else a reason. */
    String ingest(MediaService.Job job, Path out, MediaService packager) throws Exception;
}
