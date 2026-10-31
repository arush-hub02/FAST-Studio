package com.faststudio.v1.controllers.partner;

import com.faststudio.v1.core.partner.PartnerInterceptor;
import com.faststudio.v1.dto.partner.PartnerDto.Client;
import com.faststudio.v1.dto.partner.PartnerDto.Created;
import com.faststudio.v1.dto.partner.PartnerDto.NewClient;
import com.faststudio.v1.services.partner.ApiClientServiceV1;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A partner organization's API clients. The token exchange itself is in controllers/auth/OAuthTokenControllerV1. */
@RestController
@RequestMapping("/api/partners/{partner}/clients")
public class ApiClientControllerV1 {
    private final ApiClientServiceV1 clients;

    public ApiClientControllerV1(ApiClientServiceV1 clients) { this.clients = clients; }

    @GetMapping
    List<Client> list(HttpServletRequest req) { return clients.list(PartnerInterceptor.current(req)); }

    @PostMapping
    Created create(@Valid @RequestBody NewClient r, HttpServletRequest req) { return clients.create(PartnerInterceptor.current(req), r); }

    @PostMapping("/{clientId}/revoke")
    Map<String, String> revoke(@PathVariable UUID clientId, HttpServletRequest req) { return clients.revoke(PartnerInterceptor.current(req), clientId); }
}
