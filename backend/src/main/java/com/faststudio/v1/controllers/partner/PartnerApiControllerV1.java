package com.faststudio.v1.controllers.partner;

import com.faststudio.v1.core.partner.PartnerInterceptor;
import com.faststudio.v1.core.partner.PartnerScope;
import com.faststudio.v1.core.partner.PartnerScopes;
import com.faststudio.v1.dto.partner.PartnerDto.*;
import com.faststudio.v1.services.partner.PartnerApiServiceV1;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What partners (and their API clients) read. {@link PartnerInterceptor} checks the caller, the sharing, the
 * {@link PartnerScope} on each route and the channel before any of these run. Read-only routes use read-only transactions.
 */
@RestController
@RequestMapping("/api/partners/{partner}")
public class PartnerApiControllerV1 {
    private final PartnerApiServiceV1 partner;

    public PartnerApiControllerV1(PartnerApiServiceV1 partner) { this.partner = partner; }

    @GetMapping("/links")
    List<Shared> links(HttpServletRequest req) { return partner.links(PartnerInterceptor.current(req)); }

    @PostMapping("/links/{linkId}/accept")
    Map<String, String> accept(@PathVariable UUID linkId, HttpServletRequest req) { return partner.accept(PartnerInterceptor.current(req), linkId); }

    @GetMapping("/sources/{owner}/channels")
    @PartnerScope(PartnerScopes.CHANNELS)
    @Transactional(readOnly = true)
    List<PartnerChannel> channels(HttpServletRequest req) { return partner.channels(link(req)); }

    @GetMapping("/sources/{owner}/channels/{channelId}")
    @PartnerScope(PartnerScopes.CHANNELS)
    @Transactional(readOnly = true)
    PartnerChannel channel(@PathVariable UUID channelId, HttpServletRequest req) { return partner.channel(link(req), channelId); }

    @GetMapping("/sources/{owner}/channels/{channelId}/epg")
    @PartnerScope(PartnerScopes.EPG)
    @Transactional(readOnly = true)
    List<Programme> epg(@PathVariable UUID channelId, @RequestParam(defaultValue = "24") int hours, HttpServletRequest req) {
        return partner.epg(link(req), channelId, hours);
    }

    @GetMapping(value = "/sources/{owner}/channels/{channelId}/epg.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @PartnerScope(PartnerScopes.EPG)
    @Transactional(readOnly = true)
    String xmltv(@PathVariable UUID channelId, HttpServletRequest req) { return partner.xmltv(link(req), channelId); }

    @GetMapping("/sources/{owner}/channels/{channelId}/stream")
    @PartnerScope(PartnerScopes.STREAM)
    @Transactional(readOnly = true)
    Stream stream(@PathVariable UUID channelId, HttpServletRequest req) { return partner.stream(link(req), channelId); }

    @GetMapping("/sources/{owner}/channels/{channelId}/breaks")
    @PartnerScope(PartnerScopes.MARKERS)
    @Transactional(readOnly = true)
    List<AdBreak> breaks(@PathVariable UUID channelId, @RequestParam(defaultValue = "6") int hours, HttpServletRequest req) {
        return partner.breaks(link(req), channelId, hours);
    }

    @GetMapping("/sources/{owner}/channels/{channelId}/delivery")
    @PartnerScope(PartnerScopes.DELIVERY)
    @Transactional(readOnly = true)
    Delivery delivery(@PathVariable UUID channelId, @RequestParam(defaultValue = "7") int days, HttpServletRequest req) {
        return partner.delivery(link(req), channelId, days);
    }

    /** The sharing this /sources/{owner}/… request is allowed through. */
    private static PartnerInterceptor.Link link(HttpServletRequest req) { return PartnerInterceptor.current(req).requireLink(); }
}
