package com.faststudio.v1_0.controllers.studio.distribution;

import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.dto.distribution.DistributionDto.DestinationRequest;
import com.faststudio.v1_0.dto.distribution.DistributionDto.PublishResult;
import com.faststudio.v1_0.services.distribution.DistributionServiceV1_0;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Destinations and channel delivery (Distribution screen). Rules live in {@link DistributionServiceV1_0}. */
@RestController
@RequestMapping("/api/orgs/{org}")
public class DistributionControllerV1_0 {
    private final DistributionServiceV1_0 distribution;

    public DistributionControllerV1_0(DistributionServiceV1_0 distribution) { this.distribution = distribution; }

    @GetMapping("/destinations")
    Map<String, Object> list(Tenant t) { return distribution.overview(t); }

    @PostMapping("/destinations")
    Map<String, UUID> create(Tenant t, @Valid @RequestBody DestinationRequest r) { return distribution.createDestination(t, r); }

    @PostMapping("/channels/{channelId}/distribute/{destinationId}")
    PublishResult publish(Tenant t, @PathVariable UUID channelId, @PathVariable UUID destinationId) {
        return distribution.publish(t, channelId, destinationId);
    }
}
