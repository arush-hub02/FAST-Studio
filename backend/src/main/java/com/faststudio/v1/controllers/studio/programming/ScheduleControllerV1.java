package com.faststudio.v1.controllers.studio.programming;

import com.faststudio.v1.core.common.Data;
import com.faststudio.v1.core.tenant.Tenant;
import com.faststudio.v1.dto.programming.ScheduleDto.*;
import com.faststudio.v1.services.programming.EpgServiceV1;
import com.faststudio.v1.services.programming.ScheduleServiceV1;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Schedule editor, publishing and guide of one channel. Rules live in {@link ScheduleServiceV1}. */
@RestController
@RequestMapping("/api/orgs/{org}/channels/{id}")
public class ScheduleControllerV1 {
    private final ScheduleServiceV1 schedule;
    private final EpgServiceV1 epg;
    private final Data data;

    public ScheduleControllerV1(ScheduleServiceV1 schedule, EpgServiceV1 epg, Data data) {
        this.schedule = schedule;
        this.epg = epg;
        this.data = data;
    }

    @GetMapping("/schedule")
    View view(Tenant t, @PathVariable UUID id, @RequestParam(required = false) Instant from, @RequestParam(defaultValue = "26") int hours) {
        return schedule.view(t, id, from, hours);
    }

    @PostMapping("/schedule/autofill")
    EditResult autofill(Tenant t, @PathVariable UUID id, @RequestBody AutofillRequest r) { return schedule.autofill(t, id, r); }

    @PostMapping("/schedule/entries")
    EditResult add(Tenant t, @PathVariable UUID id, @RequestBody AddRequest r) { return schedule.add(t, id, r); }

    @DeleteMapping("/schedule/entries/{entryId}")
    EditResult remove(Tenant t, @PathVariable UUID id, @PathVariable UUID entryId, @RequestParam int expectedRevision,
                      @RequestParam(defaultValue = "true") boolean wholeProgramme) {
        return schedule.remove(t, id, entryId, expectedRevision, wholeProgramme);
    }

    @PostMapping("/schedule/close-gaps")
    EditResult closeGaps(Tenant t, @PathVariable UUID id, @RequestBody RevisionRequest r) { return schedule.closeGaps(t, id, r); }

    @PostMapping("/schedule/clear")
    EditResult clear(Tenant t, @PathVariable UUID id, @RequestBody RevisionRequest r) { return schedule.clear(t, id, r); }

    @PostMapping("/schedule/restore")
    EditResult restore(Tenant t, @PathVariable UUID id, @RequestBody RestoreRequest r) { return schedule.restore(t, id, r); }

    @PostMapping("/schedule/publish")
    PublishResult publish(Tenant t, @PathVariable UUID id, @RequestBody PublishRequest r) { return schedule.publish(t, id, r); }

    @GetMapping("/epg")
    List<Programme> epg(Tenant t, @PathVariable UUID id, @RequestParam(required = false) Instant from, @RequestParam(defaultValue = "24") int hours) {
        Instant f = from == null ? Instant.now().minusSeconds(3600) : from;
        return epg.epgWithThumbnails(t.orgId(), data.channel(t.orgId(), id), f, f.plusSeconds(Math.min(hours, 168) * 3600L));
    }

    @GetMapping(value = "/epg.xml", produces = MediaType.APPLICATION_XML_VALUE)
    String xmltv(Tenant t, @PathVariable UUID id) { return epg.xmltv(t.orgId(), t.slug(), data.channel(t.orgId(), id)); }
}
