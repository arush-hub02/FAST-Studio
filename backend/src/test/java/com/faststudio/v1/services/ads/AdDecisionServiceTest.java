package com.faststudio.v1.services.ads;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.common.Secrets;
import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.repositories.ads.AdSourceRepository;
import com.faststudio.v1.services.ads.sources.IAdSource;
import com.faststudio.v1.services.tenancy.TenantConfigurationServiceV1;
import com.faststudio.v1.util.cloudflare.EdgePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
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
                mock(TenantConfigurationServiceV1.class), mock(Secrets.class));
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
}
