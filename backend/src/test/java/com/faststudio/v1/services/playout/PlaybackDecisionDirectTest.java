package com.faststudio.v1.services.playout;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.repositories.playout.PlaybackRepository;
import com.faststudio.v1.services.ads.AdDecisionServiceV1;
import com.faststudio.v1.services.ads.ViewerContextService;
import com.faststudio.v1.services.ads.sources.VastSource;
import com.faststudio.v1.services.programming.EpgServiceV1;
import com.faststudio.v1.util.cloudflare.EdgeClient;
import com.faststudio.v1.util.cloudflare.EdgePublisher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A Direct ad has nothing of ours on the edge, so it must not cut the viewer's break short. */
class PlaybackDecisionDirectTest {
    @Test
    void directSlotIsKeptWithoutAnEdgeCopy() {
        var playout = mock(PlayoutServiceV1.class);
        var publisher = mock(EdgePublisher.class);
        var r2 = new AdDecisionServiceV1.Slot(UUID.randomUUID(), "Ours", "A", "acme/ad.1", 15_000, null, "Direct-sold", Map.of());
        var direct = new AdDecisionServiceV1.Slot(UUID.randomUUID(), "GAM: Spot", "B", null, 30_000, null, "GAM · Direct", Map.of(),
                null, AdDecisionServiceV1.Slot.DIRECT, "https://ads.cdn/a/master.m3u8");
        UUID brk = UUID.randomUUID();
        when(playout.decision(any(), any(), any(), anyBoolean()))
                .thenReturn(new AdDecisionServiceV1.Decision(brk, 60_000, List.of(direct, r2), 45_000, List.of(), "Filled"));
        when(publisher.stored("acme/ad.1")).thenReturn(true);
        var service = new PlaybackServiceV1(mock(Data.class), mock(PlaybackRepository.class), playout, mock(EpgServiceV1.class),
                mock(ViewerContextService.class), mock(VastSource.class), mock(EdgeClient.class), publisher, new JsonMapper());
        var d = service.decision(UUID.randomUUID(), brk, "1.2.3.4", "ua", false);
        assertEquals(2, d.slots().size());
        assertEquals(45_000, d.filledMs());
    }
}
