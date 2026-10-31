package com.faststudio.v1.controllers.studio.monitoring;

import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.ops.OpsDto.*;
import com.faststudio.v1.services.ops.AvailVerifier;
import com.faststudio.v1.services.ops.MonitoringServiceV1;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Home dashboard, alerts, audit log, avail checks and analytics of a project. Rules live in {@link MonitoringServiceV1}. */
@RestController
@RequestMapping("/api/orgs/{org}")
public class OpsControllerV1 {
    private final MonitoringServiceV1 monitoring;

    public OpsControllerV1(MonitoringServiceV1 monitoring) { this.monitoring = monitoring; }

    @GetMapping("/dashboard")
    Dashboard dashboard(Tenant t) { return monitoring.dashboard(t); }

    @GetMapping("/alerts")
    List<Alert> alerts(Tenant t, @RequestParam(defaultValue = "open") String filter) { return monitoring.alerts(t, filter); }

    @PostMapping("/alerts/{id}/acknowledge")
    Map<String, String> ack(Tenant t, @PathVariable UUID id) { return monitoring.acknowledge(t, id); }

    @PostMapping("/alerts/{id}/resolve")
    Map<String, String> resolve(Tenant t, @PathVariable UUID id, @Valid @RequestBody ResolveRequest r) { return monitoring.resolve(t, id, r); }

    @PostMapping("/alerts/scan")
    List<Alert> scanNow(Tenant t) { return monitoring.scanNow(t); }

    @GetMapping("/channels/{id}/avails")
    List<AvailVerifier.Result> avails(Tenant t, @PathVariable UUID id) { return monitoring.avails(t, id); }

    @GetMapping("/audit")
    List<AuditRow> audit(Tenant t, @RequestParam(defaultValue = "100") int limit) { return monitoring.audit(t, limit); }

    @GetMapping("/analytics")
    Analytics analytics(Tenant t) { return monitoring.analytics(t); }
}
