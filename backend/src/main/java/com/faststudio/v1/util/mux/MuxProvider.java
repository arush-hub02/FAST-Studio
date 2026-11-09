package com.faststudio.v1.util.mux;

import com.faststudio.v1.util.mediaProvider.Capability;
import com.faststudio.v1.util.mediaProvider.IDirectUpload;
import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.util.mediaProvider.Hls;
import com.faststudio.v1.util.mediaProvider.IMediaProvider;
import com.faststudio.v1.util.mediaProvider.IPlayback;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Mux as a media provider: the neutral catalogue, direct-upload and playback calls, translated to {@link MuxClientService}. */
@Component
public class MuxProvider implements IMediaProvider, IDirectUpload, IPlayback {
    /** Signed playlists are read with a short-lived token; the edge gets its own (EdgePublisher). */
    static final long READ_TOKEN_S = 600;
    public static final String KEY = "mux";
    private final MuxClientService mux;

    public MuxProvider(MuxClientService mux) { this.mux = mux; }

    @Override
    public String key() { return KEY; }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.CATALOGUE, Capability.DIRECT_UPLOAD, Capability.WEBHOOKS, Capability.PLAYBACK);
    }

    static MuxClientService.Account account(Credentials c) { return new MuxClientService.Account(c.id(), c.secret(), null, "", c.scope()); }

    /** A Mux asset in neutral terms (listings and webhook payloads). */
    public static RemoteAsset normalize(MuxClientService.Asset x) {
        String status = switch (x.status()) {
            case "ready" -> "READY";
            case "errored" -> "ERRORED";
            default -> "PREPARING";
        };
        return new RemoteAsset(x.id(), status, x.title(), x.durationMs(), x.uploadId());
    }

    @Override
    public void check(Credentials c) throws InterruptedException { mux.page(account(c), null); }

    @Override
    public Page page(Credentials c, String checkpoint) throws InterruptedException {
        MuxClientService.Page p = mux.page(account(c), checkpoint);
        return new Page(p.assets().stream().map(MuxProvider::normalize).toList(), p.next());
    }

    @Override
    public Upload upload(Credentials c, String corsOrigin, String title, int maxHeight) throws InterruptedException {
        MuxClientService.Upload u = mux.upload(account(c), corsOrigin, title, maxHeight);
        return new Upload(u.id(), u.url());
    }

    @Override
    public RemoteAsset uploaded(Credentials c, String uploadId) throws InterruptedException {
        MuxClientService.UploadState s = mux.uploadState(account(c), uploadId);
        if (s.status().equals("errored") || s.status().equals("cancelled") || s.status().equals("timed_out"))
            return new RemoteAsset("", "ERRORED", null, 0, uploadId);
        if (s.assetId() == null) return null;
        MuxClientService.Asset a = mux.asset(account(c), s.assetId());
        RemoteAsset r = normalize(a);
        // a fresh asset may not list its upload yet; this one is ours
        return new RemoteAsset(r.externalId(), r.status(), r.title(), r.durationMs(), uploadId);
    }

    @Override
    public Playback playback(Credentials c, String externalId) throws InterruptedException {
        MuxClientService.Asset a = mux.asset(account(c), externalId);
        if (a == null || "errored".equals(a.status())) throw ApiException.conflict("VIDEO_ERRORED", "The library couldn't process this video, so it can't stream.");
        if (!"ready".equals(a.status())) return null;
        String id = a.playbackId();
        boolean signed = a.signed();
        if (id == null) throw ApiException.conflict("NO_PLAYBACK_ID", "This library video has no playback ID. Add a public or signed playback ID to it in the library.");
        String master = mux.playlist(streamUrl(c, id, signed, System.currentTimeMillis() / 1000 + READ_TOKEN_S));
        List<Hls.Variant> variants = Hls.master(master, URI.create(mux.streamUrl(id, null)));
        if (variants.isEmpty()) throw ApiException.conflict("NO_RENDITIONS", "The library video has no streamable quality levels.");
        int[] first = null;
        boolean fmp4 = false;
        List<Rendition> renditions = new ArrayList<>();
        for (Hls.Variant v : variants) {
            Hls.Media m = Hls.media(mux.playlist(v.uri()));
            if (!m.complete() || m.segmentsMs().length == 0) throw ApiException.conflict("NOT_VOD", "The library video's playlists aren't complete yet. Try again shortly.");
            if (first == null) first = m.segmentsMs();
            else if (!aligned(first, m.segmentsMs())) throw ApiException.conflict("RENDITIONS_NOT_ALIGNED",
                    "The library video's quality levels don't share segment boundaries, so ads can't be spliced into it.");
            fmp4 |= m.fmp4();
            renditions.add(new Rendition(v.width(), v.height(), v.bandwidth()));
        }
        return new Playback(id, signed, fmp4 ? "FMP4" : "TS", renditions, first);
    }

    /** Same number of segments and every boundary within 50 ms. */
    static boolean aligned(int[] a, int[] b) {
        if (a.length != b.length) return false;
        long ta = 0, tb = 0;
        for (int i = 0; i < a.length; i++) {
            ta += a[i];
            tb += b[i];
            if (Math.abs(ta - tb) > 50) return false;
        }
        return true;
    }

    @Override
    public String streamUrl(Credentials c, String playbackId, boolean signed, long expEpochS) {
        return mux.streamUrl(playbackId, signed ? MuxSigner.token(c.signingKeyId(), c.signingKey(), playbackId, "v", expEpochS) : null);
    }

    @Override
    public String thumbnailUrl(Credentials c, String playbackId, boolean signed, long expEpochS) {
        return mux.thumbnailUrl(playbackId, signed ? MuxSigner.token(c.signingKeyId(), c.signingKey(), playbackId, "t", expEpochS) : null);
    }
}
