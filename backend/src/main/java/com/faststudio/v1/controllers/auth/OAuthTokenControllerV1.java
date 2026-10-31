package com.faststudio.v1.controllers.auth;

import com.faststudio.v1.core.auth.Throttle;
import com.faststudio.v1.services.partner.ApiClientServiceV1;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** OAuth 2.0 client-credentials token endpoint for partner API clients (RFC 6749 §4.4). Responses are never cached. */
@RestController
public class OAuthTokenControllerV1 {
    private final ApiClientServiceV1 clients;
    private final Throttle throttle;

    public OAuthTokenControllerV1(ApiClientServiceV1 clients, Throttle throttle) {
        this.clients = clients;
        this.throttle = throttle;
    }

    @PostMapping(value = "/oauth/token", consumes = {"application/x-www-form-urlencoded", "multipart/form-data"})
    ResponseEntity<Map<String, Object>> token(@RequestParam("grant_type") String grantType,
                                              @RequestParam(value = "client_id", required = false) String id,
                                              @RequestParam(value = "client_secret", required = false) String secret,
                                              @RequestParam(value = "scope", required = false) String scope, HttpServletRequest req) {
        throttle.check(Throttle.CLIENT_IP, Throttle.ip(req));
        var r = clients.token(grantType, id, secret, scope, req.getHeader("Authorization"));
        if (r.status() == HttpStatus.UNAUTHORIZED) throttle.record(Throttle.CLIENT_IP, Throttle.ip(req));
        return ResponseEntity.status(r.status()).header("Cache-Control", "no-store").body(r.body());
    }
}
