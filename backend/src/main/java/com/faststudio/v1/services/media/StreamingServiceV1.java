package com.faststudio.v1.services.media;

import com.faststudio.v1.core.common.ApiException;
import com.faststudio.v1.core.common.Times;
import com.faststudio.v1.dto.media.AssetDto.ProcessingJob;
import com.faststudio.v1.dto.media.AssetDto.Streaming;
import com.faststudio.v1.repositories.media.AssetPlaybackRepository;
import com.faststudio.v1.services.media.MediaServiceV1.Packaged;
import com.faststudio.v1.util.mediaProvider.IPlayback;
import com.faststudio.v1.util.mediaProvider.IPlayback.Playback;
import com.faststudio.v1.util.mediaProvider.MediaProviderResolverService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Library videos that stream from their platform (docs/OVP_DELIVERY_PLAN.md): preparing one reads its playlists through
 * the provider's {@link IPlayback} adapter and records the real segment lengths, instead of copying it to R2. The edge
 * then plays the platform's segments and splices our ads and slate in. Nothing is downloaded or converted.
 */
@Service
public class StreamingServiceV1 {
    /** Segments longer than this can't be spliced cleanly (players buffer whole segments). */
    static final int MAX_SEGMENT_MS = 12_000;

    private final MediaConnectionService connections;
    private final MediaProviderResolverService providers;
    private final AssetPlaybackRepository playbacks;

    public StreamingServiceV1(MediaConnectionService connections, MediaProviderResolverService providers, AssetPlaybackRepository playbacks) {
        this.connections = connections;
        this.providers = providers;
        this.playbacks = playbacks;
    }

    /** True for a library video whose platform can stream it (source_ref "{provider}:{external id}"). */
    public boolean handles(ProcessingJob j) {
        String ref = j.sourceRef();
        if (ref == null || !ref.contains(":")) return false;
        String provider = ref.substring(0, ref.indexOf(':'));
        return !provider.contains("-") && providers.keys().contains(provider) && providers.playback(provider) != null;
    }

    /** Reads how the video streams and records it; the job then marks the asset ready (no media key: nothing on R2). */
    public Packaged resolve(UUID org, ProcessingJob j) throws InterruptedException {
        MediaConnectionService.Connection c = connections.connection(org);
        if (c == null) return Packaged.failed(MediaConnectionService.NOT_CONNECTED, false);
        String provider = j.sourceRef().substring(0, j.sourceRef().indexOf(':'));
        IPlayback adapter = providers.playback(provider);
        if (adapter == null || !provider.equals(c.provider())) return Packaged.failed("The project's library connection can't stream this video.", false);
        Playback p;
        try {
            p = adapter.playback(c.credentials(), j.sourceRef().substring(provider.length() + 1));
        } catch (ApiException e) {
            return Packaged.failed(e.getMessage(), e.status.is5xxServerError());
        }
        if (p == null) return Packaged.failed("The library is still preparing this video. It will be tried again.", true);
        // ponytail: fMP4 programmes need fMP4 ads and slate too (EXT-X-MAP); add an fMP4 packaging lane when a platform needs it
        if (p.format().equals("FMP4")) return Packaged.failed("This library video streams as fMP4. Only MPEG-TS streams can carry our ads and slate today.", false);
        if (p.durationMs() < Times.SEG_MS) return Packaged.failed("The video is shorter than " + Times.SEG_S + " seconds.", false);
        for (int d : p.segmentsMs())
            if (d <= 0 || d > MAX_SEGMENT_MS) return Packaged.failed("The library video has a " + d / 1000.0 + " s segment; streaming needs segments of at most "
                    + MAX_SEGMENT_MS / 1000 + " s.", false);
        playbacks.save(org, j.id(), provider, p);
        return new Packaged(null, j.mediaKey(), scheduled(p.durationMs()), detail(p), null, false);
    }

    /** Length a programme takes in the schedule: whole 5 s slots, so the schedule stays on the grid (the edge fills the rest with slate). */
    public static long scheduled(long durationMs) { return Math.ceilDiv(durationMs, Times.SEG_MS) * Times.SEG_MS; }

    static String detail(Playback p) {
        String levels = p.renditions().stream().map(r -> r.height() + "p").distinct().collect(Collectors.joining(", "));
        double avg = p.durationMs() / 1000.0 / p.segmentsMs().length;
        return String.format(Locale.ROOT, "Streams from the video library: %d segments (about %.1f s each), %s, %s%s", p.segmentsMs().length, avg,
                levels.isEmpty() ? "quality levels unknown" : levels, p.format().equals("FMP4") ? "fMP4" : "MPEG-TS", p.signed() ? ", signed playback" : "");
    }


    /** The streaming details of these assets of the project; one connection lookup for the whole list. */
    public Map<UUID, Streaming> streaming(UUID org, Collection<UUID> assets) {
        Map<UUID, AssetPlaybackRepository.Row> rows = playbacks.of(org, assets);
        if (rows.isEmpty()) return Map.of();
        MediaConnectionService.Connection c = connections.connection(org);
        long exp = (System.currentTimeMillis() / 3_600_000 + 7) * 3600;   // whole hours, so thumbnails stay cacheable
        Map<UUID, Streaming> out = new HashMap<>();
        for (AssetPlaybackRepository.Row r : rows.values()) {
            Playback p = r.playback();
            IPlayback adapter = providers.playback(r.provider());
            String thumb = null, player = null, problem = null;
            if (c == null || adapter == null || !r.provider().equals(c.provider())) problem = "The library connection is gone, so this video can't stream.";
            else {
                try {
                    thumb = adapter.thumbnailUrl(c.credentials(), p.playbackId(), p.signed(), exp);
                    player = adapter.streamUrl(c.credentials(), p.playbackId(), p.signed(), exp);
                } catch (ApiException e) {
                    problem = e.getMessage();
                }
            }
            out.put(r.assetId(), new Streaming(p.segmentsMs().length, Math.round(p.durationMs() / 10.0 / p.segmentsMs().length) / 100.0,
                    p.renditions().stream().map(IPlayback.Rendition::height).distinct().sorted().toList(), p.format(), p.signed(), thumb, player, problem));
        }
        return out;
    }
}
