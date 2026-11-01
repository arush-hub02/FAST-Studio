package com.faststudio.v1.services.media.sources;

import com.faststudio.v1.dto.media.AssetDto.ProcessingJob;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.services.media.MediaServiceV1;
import com.faststudio.v1.services.media.MuxConnectionService;
import com.faststudio.v1.util.mux.MuxClientService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Content connector for a tenant's Mux library. Mux holds the tenant's catalogue; we pull each asset's original
 * (master) file once and package it into our own 5 s segments on R2, so viewers are never served, or billed per
 * minute, by Mux. HTTP is {@link MuxClientService}; credentials are {@link MuxConnectionService}.
 */
@Component
public class MuxSource implements IContentSource {
    private static final Logger log = LoggerFactory.getLogger(MuxSource.class);
    /** asset.source_ref prefixes: an imported Mux asset, and a direct upload still waiting to become one. */
    public static final String REF = "mux:", UPLOAD_REF = "mux-upload:";

    private final MuxClientService mux;
    private final MuxConnectionService connections;

    public MuxSource(MuxClientService mux, MuxConnectionService connections) {
        this.mux = mux;
        this.connections = connections;
    }

    @Override
    public boolean handles(ProcessingJob job) { return job.sourceRef() != null && job.sourceRef().startsWith(REF); }

    @Override
    public String ingest(ProcessingJob j, Path out, MediaServiceV1 packager) throws Exception {
        MuxClientService.Account a = connections.account(j.orgId());
        if (a == null) return MuxConnectionService.NOT_CONNECTED;
        String url;
        try {
            url = mux.masterUrl(a, j.sourceRef().substring(REF.length()));
        } catch (ApiException e) {
            return e.getMessage();
        }
        if (url == null) throw new MediaServiceV1.Retryable("The library couldn't provide this video's original file yet. It will be tried again; or upload the file again.");
        String err = RemoteSource.encodeUrl(url, out, packager);
        if (err == null) return null;
        log.warn("Packaging {} from Mux failed: {}", j.id(), err);
        return "The library video couldn't be converted into " + Times.SEG_S + "-second segments. Try again later.";
    }
}
