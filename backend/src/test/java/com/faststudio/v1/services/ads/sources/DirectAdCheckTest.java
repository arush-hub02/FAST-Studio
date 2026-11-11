package com.faststudio.v1.services.ads.sources;

import com.faststudio.v1.services.ads.sources.DirectAdCheck.Profile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An ad server's HLS plays directly only when it fits the channel like our own R2 ads do. */
class DirectAdCheckTest {
    static final String BASE = "https://ads.cdn/a/";

    static String media(boolean fmp4, double... pieces) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:5\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        if (fmp4) b.append("#EXT-X-MAP:URI=\"init.mp4\"\n");
        for (int i = 0; i < pieces.length; i++) b.append("#EXTINF:").append(pieces[i]).append(",\nseg").append(i).append(fmp4 ? ".m4s\n" : ".ts\n");
        return b.append("#EXT-X-ENDLIST\n").toString();
    }

    static final String MASTER_FMP4_AUDIO = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",DEFAULT=YES,URI="audio/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
            v720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            v360/index.m3u8
            """;

    static final Map<String, String> FIXTURES = Map.of(
            BASE + "v720/index.m3u8", media(true, 5, 5, 5, 5, 5, 5),
            BASE + "v360/index.m3u8", media(true, 5, 5, 5, 5, 5, 5),
            BASE + "audio/index.m3u8", media(true, 5, 5, 5, 5, 5, 5));

    static Profile profile(String container, boolean audio, double... pieces) {
        return new Profile(container, audio, List.of(720), Arrays.stream(pieces).boxed().toList());
    }

    @Test
    void fmp4WithSeparateAudioAndFiveSecondPiecesFits() {
        Profile p = DirectAdCheck.parse(MASTER_FMP4_AUDIO, BASE + "master.m3u8", FIXTURES::get);
        assertEquals("FMP4", p.container());
        assertTrue(p.separateAudio());
        assertEquals(List.of(720, 360), p.heights());
        assertEquals(30_000, DirectAdCheck.slotMs(p));
        assertNull(DirectAdCheck.misfit(p, "FMP4", 30_000, 60_000));
    }

    @Test
    void aMediaPlaylistGivenAsTheAdIsOneTsVariant() {
        Profile p = DirectAdCheck.parse(media(false, 5, 5, 5), BASE + "index.m3u8", u -> null);
        assertEquals("TS", p.container());
        assertFalse(p.separateAudio());
        assertEquals(15_000, DirectAdCheck.slotMs(p));
    }

    @Test
    void shortLastPieceIsDropped() {
        assertEquals(30_000, DirectAdCheck.slotMs(profile("TS", false, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 2.3)));
        assertNull(DirectAdCheck.misfit(profile("TS", false, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 2.3), "TS", 32_300, 60_000));
    }

    @Test
    void sixSecondPiecesDoNotFit() {
        assertEquals(0, DirectAdCheck.slotMs(profile("TS", false, 6, 6, 6)));
        assertEquals("Direct not possible: pieces are not 5 s", DirectAdCheck.misfit(profile("TS", false, 6, 6, 6), "TS", 18_000, 60_000));
    }

    @Test
    void tsAdOnFmp4ChannelDoesNotFit() {
        assertEquals("Direct not possible: TS pieces on a FMP4 channel", DirectAdCheck.misfit(profile("TS", false, 5, 5), "FMP4", 10_000, 60_000));
    }

    @Test
    void muxedAudioOnFmp4ChannelDoesNotFit() {
        assertEquals("Direct not possible: no separate audio track", DirectAdCheck.misfit(profile("FMP4", false, 5, 5), "FMP4", 10_000, 60_000));
    }

    @Test
    void shorterThanOnePieceDoesNotFit() {
        assertEquals("Direct not possible: shorter than one 5 s piece", DirectAdCheck.misfit(profile("TS", false, 3.2), "TS", 3_200, 60_000));
    }

    @Test
    void lengthMustMatchTheAdServers() {
        assertEquals("Direct not possible: length differs from the ad server's", DirectAdCheck.misfit(profile("TS", false, 5, 5, 5), "TS", 30_000, 60_000));
    }

    @Test
    void longerThanLeftDoesNotFit() {
        assertEquals("Direct not possible: doesn't fit the time left", DirectAdCheck.misfit(profile("TS", false, 5, 5, 5, 5), "TS", 20_000, 15_000));
    }

    @Test
    void profileSurvivesJson() {
        Profile p = profile("FMP4", true, 5, 5, 2.5);
        assertEquals(p, DirectAdCheck.fromJson(DirectAdCheck.toJson(p)));
        assertEquals(p, DirectAdCheck.fromJson("{\"container\":\"FMP4\",\"separateAudio\":true,\"heights\":[720],\"pieces\":[5.0,5.0,2.5]}"),
                "checks saved before byte ranges and keys were recorded still read");
    }

    @Test
    void singleFileHlsWithByteRangesDoesNotFit() {
        Profile p = DirectAdCheck.parse("#EXTM3U\n#EXTINF:5.0,\n#EXT-X-BYTERANGE:1000@0\nad.ts\n#EXTINF:5.0,\n#EXT-X-BYTERANGE:1000@1000\nad.ts\n#EXT-X-ENDLIST\n", BASE + "i.m3u8", u -> null);
        assertEquals("Direct not possible: single-file HLS (byte ranges)", DirectAdCheck.misfit(p, "TS", 10_000, 60_000));
    }

    @Test
    void encryptedPiecesDoNotFit() {
        Profile p = DirectAdCheck.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:5.0,\na.ts\n#EXT-X-ENDLIST\n", BASE + "i.m3u8", u -> null);
        assertEquals("Direct not possible: encrypted pieces", DirectAdCheck.misfit(p, "TS", 5_000, 60_000));
        Profile clear = DirectAdCheck.parse("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:5.0,\na.ts\n#EXT-X-ENDLIST\n", BASE + "i.m3u8", u -> null);
        assertNull(DirectAdCheck.misfit(clear, "TS", 5_000, 60_000));
    }

    @Test
    void separateAudioOnATsChannelDoesNotFit() {
        assertEquals("Direct not possible: audio is a separate track, the TS channel needs it in the pieces",
                DirectAdCheck.misfit(profile("TS", true, 5, 5), "TS", 10_000, 60_000));
    }
}
