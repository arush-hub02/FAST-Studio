package com.faststudio.v1.controllers.studio.media;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.media.AssetDto.SlateView;
import com.faststudio.v1.services.media.SlateServiceV1;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** The project's own slate (Ads section). Rules live in {@link SlateServiceV1}. */
@RestController
@RequestMapping("/api/orgs/{org}/slate")
public class SlateControllerV1 {
    private final SlateServiceV1 slates;

    public SlateControllerV1(SlateServiceV1 slates) { this.slates = slates; }

    @GetMapping
    List<SlateView> list(Tenant t) { return slates.list(t); }

    @PostMapping
    SlateView upload(Tenant t, @RequestParam("file") MultipartFile file,
                     @RequestParam(defaultValue = "false") boolean nextSchedule) throws IOException {
        return slates.upload(t, file, nextSchedule);
    }

    @DeleteMapping
    ResponseEntity<Void> remove(Tenant t) {
        slates.remove(t);
        return ResponseEntity.noContent().build();
    }
}
