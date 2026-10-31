package com.faststudio.v1.controllers.studio.distribution;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.distribution.DistributionDto.*;
import com.faststudio.v1.services.distribution.PartnerSharingServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer side of sharing channels with partner organizations. Rules live in {@link PartnerSharingServiceV1}. */
@RestController
@RequestMapping("/api/orgs/{org}/partners")
public class PartnerSharingControllerV1 {
    private final PartnerSharingServiceV1 sharing;

    public PartnerSharingControllerV1(PartnerSharingServiceV1 sharing) { this.sharing = sharing; }

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
