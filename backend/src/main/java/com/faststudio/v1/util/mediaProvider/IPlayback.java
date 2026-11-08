package com.faststudio.v1.util.mediaProvider;

import java.util.List;

/**
 * Optional capability ({@link Capability#PLAYBACK}): the platform streams the video itself. The edge reads its HLS
 * playlists and splices our ads and slate in, so programmes are never copied to our storage.
 */
public interface IPlayback {
    record Rendition(int width, int height, int bandwidth) {}

    /**
     * How a video plays: its playback id, whether URLs must be signed, the container (TS or FMP4), the quality levels and
     * the real segment lengths (the same in every quality level).
     */
    record Playback(String playbackId, boolean signed, String format, List<Rendition> renditions, int[] segmentsMs) {
        public long durationMs() {
            long t = 0;
            for (int d : segmentsMs) t += d;
            return t;
        }
    }

    /** Reads the video's playlists; null while the platform is still preparing it. Errors are tenant-safe ApiExceptions. */
    Playback playback(IMediaProvider.Credentials c, String externalId) throws InterruptedException;

    /** Master playlist URL for players and the edge; signed playback carries a token valid until expEpochS. */
    String streamUrl(IMediaProvider.Credentials c, String playbackId, boolean signed, long expEpochS);

    /** A still image of the video, signed the same way. */
    String thumbnailUrl(IMediaProvider.Credentials c, String playbackId, boolean signed, long expEpochS);
}
