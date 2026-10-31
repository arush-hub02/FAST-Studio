package com.faststudio.v1.controllers.studio.channels;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.channels.ChannelDto.*;
import com.faststudio.v1.services.channels.ChannelServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Channels of the project (Channels screen). Rules live in {@link ChannelServiceV1}. */
@RestController
@RequestMapping("/api/orgs/{org}/channels")
public class ChannelControllerV1 {
    private final ChannelServiceV1 channels;

    public ChannelControllerV1(ChannelServiceV1 channels) { this.channels = channels; }

    @GetMapping
    List<ChannelView> list(Tenant t) { return channels.list(t); }

    @GetMapping("/{id}")
    ChannelView get(Tenant t, @PathVariable UUID id) { return channels.get(t, id); }

    @PostMapping
    ChannelView create(Tenant t, @Valid @RequestBody CreateRequest r) { return channels.create(t, r); }

    @PatchMapping("/{id}")
    ChannelView update(Tenant t, @PathVariable UUID id, @RequestBody UpdateRequest r) { return channels.update(t, id, r); }

    @PostMapping("/{id}/go-live")
    ChannelView goLive(Tenant t, @PathVariable UUID id) { return channels.goLive(t, id); }

    @PostMapping("/{id}/stop")
    ChannelView stop(Tenant t, @PathVariable UUID id) { return channels.stop(t, id); }

    @PostMapping("/{id}/archive")
    ChannelView archive(Tenant t, @PathVariable UUID id) { return channels.archive(t, id); }
}
