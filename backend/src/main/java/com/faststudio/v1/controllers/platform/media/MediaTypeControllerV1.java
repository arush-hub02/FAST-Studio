package com.faststudio.v1.controllers.platform.media;

import com.faststudio.v1.dto.media.MediaSettingsDto.Entitlements;
import com.faststudio.v1.dto.media.MediaSettingsDto.Type;
import com.faststudio.v1.dto.media.MediaSettingsDto.TypeRequest;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Platform media type catalogue and which types each project may use. */
@RestController
@RequestMapping("/api/platform")
public class MediaTypeControllerV1 {
    private final MediaSettingsServiceV1 settings;

    public MediaTypeControllerV1(MediaSettingsServiceV1 settings) { this.settings = settings; }

    @GetMapping("/media-types")
    List<Type> types() { return settings.catalog(); }

    @PostMapping("/media-types")
    List<Type> saveType(@Valid @RequestBody TypeRequest r) { return settings.saveType(r); }

    @GetMapping("/orgs/{orgId}/media-types")
    List<String> entitled(@PathVariable UUID orgId) { return settings.entitlementsForPlatform(orgId); }

    @PutMapping("/orgs/{orgId}/media-types")
    List<String> entitle(@PathVariable UUID orgId, @Valid @RequestBody Entitlements r) { return settings.entitle(orgId, r); }
}
