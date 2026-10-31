package com.faststudio.v1.controllers.studio.media;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.MediaSettingsDto.SettingsRequest;
import com.faststudio.v1.dto.media.MediaSettingsDto.View;
import com.faststudio.v1.dto.media.MediaSettingsDto.ViewerKeyRequest;
import com.faststudio.v1.services.media.MediaSettingsServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Project media/programming settings (Settings screen) and the viewer-context integration key. */
@RestController
@RequestMapping("/api/orgs/{org}/settings")
public class MediaSettingsControllerV1 {
    private final MediaSettingsServiceV1 settings;

    public MediaSettingsControllerV1(MediaSettingsServiceV1 settings) { this.settings = settings; }

    @GetMapping
    View view(Tenant t) { return settings.view(t.orgId()); }

    @PutMapping
    View save(Tenant t, @Valid @RequestBody SettingsRequest r) { return settings.save(t, r); }

    @PostMapping("/viewer-key")
    View viewerKey(Tenant t, @Valid @RequestBody ViewerKeyRequest request) { return settings.rotateViewerKey(t, request); }
}
