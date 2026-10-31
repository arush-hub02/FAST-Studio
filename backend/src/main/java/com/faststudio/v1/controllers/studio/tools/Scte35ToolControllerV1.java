package com.faststudio.v1.controllers.studio.tools;

import com.faststudio.v1.dto.ops.OpsDto.DecodeRequest;
import com.faststudio.v1.services.ops.MonitoringServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** SCTE-35 learning tool for signed-in users: decode a marker, or get a fresh example. No tenant data involved. */
@RestController
@RequestMapping("/api/tools/scte35")
public class Scte35ToolControllerV1 {
    private final MonitoringServiceV1 monitoring;

    public Scte35ToolControllerV1(MonitoringServiceV1 monitoring) { this.monitoring = monitoring; }

    @PostMapping("/decode")
    Map<String, Object> decode(@Valid @RequestBody DecodeRequest r) { return monitoring.decodeScte35(r.value()); }

    @GetMapping("/example")
    Map<String, Object> example() { return monitoring.scte35Example(); }
}
