package com.faststudio.core;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public keys that verify FAST Studio sign-in tokens, for partners and our own services (no private parts). */
@RestController
public class JwksController {
    private final TokenKeys keys;
    private final Tokens tokens;

    public JwksController(TokenKeys keys, Tokens tokens) {
        this.keys = keys;
        this.tokens = tokens;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        if (!tokens.enabled()) throw ApiException.notFound("Token sign-in");
        return keys.publicJwks();
    }
}
