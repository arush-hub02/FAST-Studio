package com.faststudio.v1_0.controllers.studio.distribution;

import com.faststudio.v1_0.core.tenant.Tenant;
import com.faststudio.v1_0.dto.distribution.DistributionDto.*;
import com.faststudio.v1_0.services.distribution.PartnerSharingServiceV1_0;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer side of sharing channels with partner organizations. Rules live in {@link PartnerSharingServiceV1_0}. */
@RestController
@RequestMapping("/api/orgs/{org}/partners")
public class PartnerSharingControllerV1_0 {
    private final PartnerSharingServiceV1_0 sharing;

    public PartnerSharingControllerV1_0(PartnerSharingServiceV1_0 sharing) { this.sharing = sharing; }

    @GetMapping("/directory")
    Directory directory(Tenant t) { return sharing.directory(t); }

    @GetMapping
    List<Sharing> list(Tenant t) { return sharing.list(t); }

    @PostMapping
    Sharing share(Tenant t, @Valid @RequestBody ShareRequest r) { return sharing.share(t, r); }

    @PutMapping("/{linkId}")
    Sharing update(Tenant t, @PathVariable UUID linkId, @Valid @RequestBody ShareRequest r) { return sharing.update(t, linkId, r); }

    @PostMapping("/{linkId}/revoke")
    Map<String, String> revoke(Tenant t, @PathVariable UUID linkId) { return sharing.revoke(t, linkId); }
}
