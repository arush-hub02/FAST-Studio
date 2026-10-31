package com.faststudio.v1.core.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * ES256 signing keys for sign-in tokens, kept in {@code <data-dir>/keys/jwt-signing-keys.json} so tokens survive a
 * restart and every instance sharing the data folder signs alike. The newest key signs; it is replaced after
 * {@code faststudio.auth.key-rotation-days}, and the previous key stays published (JWKS) so tokens it signed still
 * verify until they expire. Treat the file like a password: it is in backups, never in git (data folders are ignored).
 */
@Component
public class TokenKeys {
    private static final Logger log = LoggerFactory.getLogger(TokenKeys.class);

    private final ECKey signing;
    private final JWKSet all;

    public TokenKeys(@Value("${faststudio.data-dir}") String dataDir, @Value("${faststudio.auth.key-rotation-days:90}") int rotationDays) throws Exception {
        Path file = Path.of(dataDir, "keys", "jwt-signing-keys.json");
        List<JWK> keys = new ArrayList<>(Files.exists(file) ? JWKSet.parse(Files.readString(file)).getKeys() : List.of());
        keys.sort(Comparator.comparing((JWK k) -> k.getIssueTime() == null ? new Date(0) : k.getIssueTime()).reversed());
        boolean stale = keys.isEmpty() || keys.get(0).getIssueTime() == null
                || keys.get(0).getIssueTime().toInstant().isBefore(Instant.now().minus(Duration.ofDays(rotationDays)));
        if (stale) {
            keys.add(0, new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
                    .keyID("fs-" + UUID.randomUUID().toString().substring(0, 8)).issueTime(new Date()).generate());
            if (keys.size() > 2) keys.subList(2, keys.size()).clear();       // newest signs, previous still verifies
            Files.createDirectories(file.getParent());
            Files.writeString(file, new JWKSet(keys).toString(false), StandardCharsets.UTF_8);
            log.info("New token signing key {} ({} key(s) published)", keys.get(0).getKeyID(), keys.size());
        }
        this.signing = keys.get(0).toECKey();
        this.all = new JWKSet(keys);
    }

    public String signingKeyId() { return signing.getKeyID(); }

    public JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signing)));
    }

    /** Signature check against every published key; claim checks are added by {@link Tokens}. */
    public NimbusJwtDecoder decoder() {
        return NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(all.toPublicJWKSet())).jwsAlgorithm(SignatureAlgorithm.ES256).build();
    }

    /** Public keys only (no private part), for /.well-known/jwks.json. */
    public Map<String, Object> publicJwks() {
        return all.toJSONObject(true);
    }

}
