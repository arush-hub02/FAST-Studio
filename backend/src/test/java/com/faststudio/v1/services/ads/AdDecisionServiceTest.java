package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.dto.ads.AdsDto.Candidate;
import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.repositories.ads.AdSourceRepository;
import com.faststudio.v1.repositories.ads.CampaignRepository;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.cloudflare.EdgePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdDecisionServiceTest {
    @Test
    void earlyEmptyDecisionCanRetryAfterCreativeReachesEdge() {
        UUID org = UUID.randomUUID(), session = UUID.randomUUID(), brk = UUID.randomUUID();
        var repository = mock(AdSourceRepository.class);
        var publisher = mock(EdgePublisher.class);
        var direct = mock(IAdSource.class);
        when(direct.kind()).thenReturn("DIRECT");
        var slot = new AdDecisionServiceV1.Slot(UUID.randomUUID(), "Test ad", "Test advertiser", "test/ad.1",
                15_000, null, "Test source", Map.of());
        when(direct.fill(any(), any())).thenReturn(new IAdSource.Fill(List.of(slot), List.of()));
        var service = new AdDecisionServiceV1(mock(Data.class), repository, List.of(direct),
                mock(PlatformTransactionManager.class), new JsonMapper(), publisher,
                mock(TenantConfigurationServiceV1.class), mock(Secrets.class), mock(CampaignRepository.class));
        Instant start = Instant.now().plusSeconds(60);

        var pending = service.decide(org, session, brk, start, 30_000, null, "IN", IAdSource.Viewer.NONE, true);
        assertTrue(pending.preparing());
        assertEquals(0, pending.filledMs());
        verify(repository, never()).saveDecision(any(), any(), any(), anyString());

        when(publisher.stored(slot.mediaKey())).thenReturn(true);
        when(publisher.levels(slot.mediaKey())).thenReturn(List.of(3, 4));
        var ready = service.decide(org, session, brk, start, 30_000, null, "IN", IAdSource.Viewer.NONE, true);
        assertFalse(ready.preparing());
        assertEquals(15_000, ready.filledMs());
        assertEquals(List.of(3, 4), AdDecisionView.from(ready).slots().getFirst().levels());
        verify(repository).saveDecision(eq(org), eq(session), eq(brk), anyString());
    }

    @Test
    void housePromosFillWhatTheAdsLeftOpenAndAreNeverSold() {
        UUID org = UUID.randomUUID(), brk = UUID.randomUUID();
        var publisher = mock(EdgePublisher.class);
        when(publisher.stored(anyString())).thenReturn(true);
        var direct = mock(IAdSource.class);
        when(direct.kind()).thenReturn("DIRECT");
        var ad = new AdDecisionServiceV1.Slot(UUID.randomUUID(), "Paid ad", "Brand", "test/ad.1", 15_000, BigDecimal.TEN, "Direct", Map.of());
        when(direct.fill(any(), any())).thenReturn(new IAdSource.Fill(List.of(ad), List.of()));
        var campaigns = mock(CampaignRepository.class);
        OffsetDateTime now = OffsetDateTime.now();
        when(campaigns.candidates(org)).thenReturn(List.of(
                promo("Ident", "IN", "ACTIVE", 5_000, now), promo("Paused promo", "WW", "PAUSED", 5_000, now), promo("US only", "US", "ACTIVE", 5_000, now)));
        var service = new AdDecisionServiceV1(mock(Data.class), mock(AdSourceRepository.class), List.of(direct),
                mock(PlatformTransactionManager.class), new JsonMapper(), publisher,
                mock(TenantConfigurationServiceV1.class), mock(Secrets.class), campaigns);

        var d = service.decide(org, null, brk, Instant.now(), 30_000, null, "IN");
        assertEquals(30_000, d.filledMs());
        assertEquals(List.of("Paid ad", "Ident", "Ident", "Ident"), d.slots().stream().map(AdDecisionServiceV1.Slot::campaign).toList());
        assertEquals(0, d.slots().get(1).cpm().signum(), "house promos earn nothing");
        assertTrue(d.summary().contains("1 ad(s) and 3 house promo(s)"), d.summary());
    }

    private static Candidate promo(String name, String territories, String status, long ms, OffsetDateTime now) {
        return new Candidate(UUID.randomUUID(), name, "Channel", status, now.minusDays(1), now.plusDays(1), territories, 5, BigDecimal.ZERO,
                "READY", "test/" + name.length(), ms, "", 0, true);
    }
}
