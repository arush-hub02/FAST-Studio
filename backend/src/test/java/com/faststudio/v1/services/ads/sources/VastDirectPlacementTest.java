package com.faststudio.v1.services.ads.sources;

import com.faststudio.v1.dto.ads.AdsDto.Creative;
import com.faststudio.v1.repositories.ads.AdSourceRepository;
import com.faststudio.v1.repositories.ads.AdSourceRepository.DirectCheck;
import com.faststudio.v1.services.ads.AdDecisionServiceV1.Slot;
import com.faststudio.v1.services.ads.sources.DirectAdCheck.Profile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Under Direct, an ad whose HLS fits the channel plays from the ad server; everything else takes the R2 path. */
class VastDirectPlacementTest {
    static final String REF = "GAM|creative:ch", HLS = "https://ads.cdn/a/master.m3u8";
    final UUID org = UUID.randomUUID();
    final AdSourceRepository repository = mock(AdSourceRepository.class);
    final DirectAdCheck checker = mock(DirectAdCheck.class);
    final VastSource source = new VastSource(repository, null, checker);

    static Profile fmp4(int pieces) { return new Profile("FMP4", true, List.of(720), Collections.nCopies(pieces, 5.0)); }

    IAdSource.Config src(String delivery) {
        return new IAdSource.Config(UUID.randomUUID(), "GAM", "VAST", "https://x", 1000, 1, true, "PLATFORM", delivery);
    }

    IAdSource.Request req(String fmt) {
        return new IAdSource.Request(org, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 60_000, 60_000, "IN",
                "ch", "", null, IAdSource.Viewer.NONE, new HashSet<>(), fmt);
    }

    static VastSource.Ad ad(String mp4, String hls) { return new VastSource.Ad("Spot", "Glow", 30_000, mp4, REF, Map.of(), hls); }

    void readyR2() { when(repository.creative(org, REF)).thenReturn(Optional.of(new Creative(UUID.randomUUID(), "READY", "acme/ad.1", 30_000))); }

    Slot only(IAdSource.Fill f) {
        assertEquals(1, f.slots().size(), () -> "slots " + f.slots() + " excluded " + f.excluded());
        return f.slots().getFirst();
    }

    @Test
    void fittingHlsAdBecomesDirectSlot() {
        when(repository.directCheck(org, REF)).thenReturn(Optional.of(new DirectCheck(fmp4(6), Instant.now())));
        Slot s = only(source.place(src(IAdSource.DIRECT), req("FMP4"), List.of(ad("https://cdn/a.mp4", HLS))));
        assertEquals("DIRECT", s.delivery());
        assertNull(s.mediaKey());
        assertEquals(30_000, s.durationMs());
        assertEquals(HLS, s.hlsUrl());
        assertTrue(s.source().endsWith(" · Direct"), s.source());
        assertNotNull(s.campaignId());
        verify(repository, never()).creative(any(), any());
    }

    @Test
    void firstSightOfDirectAdUsesStoreAndStartsCheck() throws Exception {
        when(repository.directCheck(org, REF)).thenReturn(Optional.empty());
        when(checker.check(HLS)).thenReturn(fmp4(6));
        readyR2();
        Slot s = only(source.place(src(IAdSource.DIRECT), req("FMP4"), List.of(ad("https://cdn/a.mp4", HLS))));
        assertEquals("R2", s.delivery());
        verify(checker, timeout(2000)).check(HLS);
        verify(repository, timeout(2000)).saveDirectCheck(org, REF, fmp4(6));
    }

    @Test
    void staleCheckIsRefreshedAndStoreIsUsedMeanwhile() throws Exception {
        when(repository.directCheck(org, REF)).thenReturn(Optional.of(new DirectCheck(fmp4(6), Instant.now().minus(Duration.ofHours(25)))));
        when(checker.check(HLS)).thenReturn(fmp4(6));
        readyR2();
        assertEquals("R2", only(source.place(src(IAdSource.DIRECT), req("FMP4"), List.of(ad("https://cdn/a.mp4", HLS)))).delivery());
        verify(checker, timeout(2000)).check(HLS);
    }

    @Test
    void mp4OnlyAdUnderDirectUsesStore() {
        readyR2();
        assertEquals("R2", only(source.place(src(IAdSource.DIRECT), req("FMP4"), List.of(ad("https://cdn/a.mp4", null)))).delivery());
        verifyNoInteractions(checker);
    }

    @Test
    void misfitFallsBackToR2WhenReady() {
        when(repository.directCheck(org, REF)).thenReturn(Optional.of(new DirectCheck(new Profile("TS", false, List.of(720), List.of(6.0, 6.0, 6.0, 6.0, 6.0)), Instant.now())));
        readyR2();
        assertEquals("R2", only(source.place(src(IAdSource.DIRECT), req("TS"), List.of(ad("https://cdn/a.mp4", HLS)))).delivery());
    }

    @Test
    void hlsOnlyMisfitIsExcludedWithItsReason() {
        when(repository.directCheck(org, REF)).thenReturn(Optional.of(new DirectCheck(fmp4(6), Instant.now())));
        var f = source.place(src(IAdSource.DIRECT), req("TS"), List.of(ad(null, HLS)));
        assertTrue(f.slots().isEmpty());
        assertEquals("Direct not possible: FMP4 pieces on a TS channel", f.excluded().getFirst().reason());
    }

    @Test
    void storeSourceNeverPlaysDirect() {
        when(repository.directCheck(org, REF)).thenReturn(Optional.of(new DirectCheck(fmp4(6), Instant.now())));
        readyR2();
        assertEquals("R2", only(source.place(src(IAdSource.STORE_R2), req("FMP4"), List.of(ad("https://cdn/a.mp4", HLS)))).delivery());
        verifyNoInteractions(checker);
    }
}
