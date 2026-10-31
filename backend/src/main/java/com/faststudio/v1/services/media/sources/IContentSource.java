package com.faststudio.v1.services.media.sources;

import com.faststudio.v1.dto.media.AssetDto.ProcessingJob;
import com.faststudio.v1.services.media.MediaServiceV1;

import java.nio.file.Path;

/**
 * Content connector: where an asset's video comes from (an upload, a generated demo clip, later Mux and others).
 * A connector only brings the video in. Packaging into uniform 5 s segments is always ours, so playout, ad insertion
 * and delivery never depend on which connector supplied the file.
 */
public interface IContentSource {
    boolean handles(ProcessingJob job);

    /** Packages the asset into {@code out} via {@link MediaServiceV1#encode}. Returns null on success, else a reason. */
    String ingest(ProcessingJob job, Path out, MediaServiceV1 packager) throws Exception;
}
