package com.faststudio.v1.util.mediaProvider;

import com.faststudio.v1.core.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registry of the video-platform adapters (every IMediaProvider bean), looked up by the key stored on a connection. */
@Service
public class MediaProviderResolverService {
    private final Map<String, IMediaProvider> providers;

    public MediaProviderResolverService(List<IMediaProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toMap(IMediaProvider::key, Function.identity()));
    }

    public IMediaProvider get(String key) {
        IMediaProvider p = providers.get(key);
        if (p == null) throw new IllegalStateException("No media provider registered for '" + key + "'");
        return p;
    }

    public boolean supports(String key, Capability c) {
        IMediaProvider p = providers.get(key);
        return p != null && p.capabilities().contains(c);
    }

    /** Refuses (409 NOT_SUPPORTED, tenant-safe) when the connected platform can't do this. */
    public void require(String key, Capability c) {
        if (!supports(key, c)) throw ApiException.conflict("NOT_SUPPORTED", switch (c) {
            case CATALOGUE -> "The connected library can't list its videos.";
            case DIRECT_UPLOAD -> "The connected library doesn't accept direct uploads.";
            case SOURCE_FILE -> "Videos from the connected library can't be prepared for broadcast yet.";
            case WEBHOOKS -> "The connected library doesn't send updates.";
        });
    }

    public IDirectUpload directUpload(String key) {
        require(key, Capability.DIRECT_UPLOAD);
        if (get(key) instanceof IDirectUpload u) return u;
        throw new IllegalStateException("Provider '" + key + "' claims DIRECT_UPLOAD but doesn't implement it");
    }
}
