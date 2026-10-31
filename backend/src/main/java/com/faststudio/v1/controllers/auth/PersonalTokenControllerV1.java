package com.faststudio.v1.controllers.auth;

import com.faststudio.v1.core.auth.Auth;
import com.faststudio.v1.dto.auth.AuthDto.CreatePersonalToken;
import com.faststudio.v1.dto.auth.AuthDto.CreatedPersonalToken;
import com.faststudio.v1.dto.auth.AuthDto.PersonalToken;
import com.faststudio.v1.services.auth.PersonalTokenServiceV1;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The signed-in person's own personal access tokens (for scripts and MCP clients). A token can't manage tokens. */
@RestController
@RequestMapping("/api/auth/personal-tokens")
public class PersonalTokenControllerV1 {
    private final PersonalTokenServiceV1 tokens;

    public PersonalTokenControllerV1(PersonalTokenServiceV1 tokens) { this.tokens = tokens; }

    @GetMapping
    List<PersonalToken> list() { return tokens.list(Auth.userId()); }

    /** The answer holds the token itself, shown this once: never cached. */
    @PostMapping
    ResponseEntity<CreatedPersonalToken> create(@Valid @RequestBody CreatePersonalToken body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.create(Auth.userId(), body));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@PathVariable UUID id) {
        tokens.revoke(Auth.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
