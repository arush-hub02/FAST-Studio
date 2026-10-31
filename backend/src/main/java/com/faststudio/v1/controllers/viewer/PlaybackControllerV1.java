package com.faststudio.v1.controllers.viewer;

import com.faststudio.v1.dto.media.AdDecisionView;
import com.faststudio.v1.dto.playout.PlaybackDto.*;
import com.faststudio.v1.services.playout.PlaybackServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Public viewer-side API (no admin session): start a session, ad decisions and beacons (also called by the Worker),
 * privacy opt-out and the player overlay. Rules live in {@link PlaybackServiceV1}.
 */
@RestController
@RequestMapping("/play")
public class PlaybackControllerV1 {
    private final PlaybackServiceV1 playback;

    public PlaybackControllerV1(PlaybackServiceV1 playback) { this.playback = playback; }

    @PostMapping("/sessions")
    Started start(@Valid @RequestBody StartRequest r) { return playback.start(r); }

    @GetMapping("/{session}/decisions/{breakId}")
    AdDecisionView decision(@PathVariable UUID session, @PathVariable UUID breakId,
                            @RequestHeader(value = "X-Viewer-IP", defaultValue = "") String ip,
                            @RequestHeader(value = "X-Viewer-UA", defaultValue = "") String ua) {
        return playback.decision(session, breakId, ip, ua);
    }

    @PostMapping("/{session}/privacy/contextual")
    ResponseEntity<Void> contextual(@PathVariable UUID session) {
        playback.contextualOnly(session);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{session}/beacons")
    ResponseEntity<Void> beacon(@PathVariable UUID session, @RequestBody Beacon b) {
        playback.beacon(session, b);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{session}/info")
    Info info(@PathVariable UUID session) { return playback.info(session); }
}
