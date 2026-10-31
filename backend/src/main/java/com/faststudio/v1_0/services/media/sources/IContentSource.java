package com.faststudio.v1_0.services.media.sources;

import com.faststudio.v1_0.services.media.MediaServiceV1_0;

import java.nio.file.Path;

/**
 * Content connector: where an asset's video comes from (an upload, a generated demo clip, later Mux and others).
 * A connector only brings the video in. Packaging into uniform 5 s segments is always ours, so playout, ad insertion
 * and delivery never depend on which connector supplied the file.
 */
public interface IContentSource {
    boolean handles(MediaServiceV1_0.Job job);

    /** Packages the asset into {@code out} via {@link MediaServiceV1_0#encode}. Returns null on success, else a reason. */
    String ingest(MediaServiceV1_0.Job job, Path out, MediaServiceV1_0 packager) throws Exception;
}
